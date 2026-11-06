package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.raop.Sdp;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming writer for Apple's CAF container carrying raw ALAC packets.
 *
 * <p>AirPlay senders deliver one complete ALAC frame per RTP packet, so the recording pipeline can
 * keep the original lossless frames without decoding them in Java. ffmpeg converts the result to
 * m4a/mp3 with tags and artwork.
 *
 * <p>Layout written: {@code caff} header, {@code desc}, {@code chan}, {@code kuki}, {@code data}
 * (streamed, size patched on close) and finally {@code pakt} with the packet table.
 */
public final class CafWriter implements Closeable {

    private final RandomAccessFile file;
    private final Path path;
    private final List<Integer> packetSizes = new ArrayList<>();
    private final long dataPayloadStart;
    private long framesWritten;
    private boolean closed;

    public CafWriter(Path path, Sdp.AlacConfig config) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        this.dataPayloadStart = writeHeader(config);
    }

    public Path path() {
        return path;
    }

    public synchronized long framesWritten() {
        return framesWritten;
    }

    public synchronized long durationMillis(int sampleRate) {
        return sampleRate <= 0 ? 0 : Math.round((framesWritten * 1000.0) / sampleRate);
    }

    public synchronized void writeFrame(byte[] data, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("writer closed");
        }
        file.write(data, offset, length);
        packetSizes.add(length);
        framesWritten += framesPerPacket;
    }

    private int framesPerPacket;
    private byte[] silentFrame;

    /**
     * Writes {@code packets} ALAC frames of silence, so a run of packets the sender never resent
     * keeps its place in time instead of being spliced out of the recording.
     *
     * @return false when the stream layout has no silent frame (more than two channels)
     */
    public synchronized boolean writeSilence(int packets) throws IOException {
        if (silentFrame == null || packets <= 0) {
            return false;
        }
        for (int i = 0; i < packets; i++) {
            writeFrame(silentFrame, 0, silentFrame.length);
        }
        return true;
    }

    /**
     * One ALAC frame that decodes to {@code frameLength} samples of digital silence: a single
     * channel element (mono SCE or stereo CPE) flagged as uncompressed, all-zero samples, then the
     * END element. Every ALAC decoder handles this escape form; no encoder is needed.
     */
    public static byte[] silentFrame(Sdp.AlacConfig config) {
        int channels = config.channels();
        if (channels < 1 || channels > 2) {
            return null;
        }
        long bits =
            3 +
            4 +
            12 +
            1 +
            2 +
            1 + // element header
            (long) config.frameLength() * channels * config.bitDepth() + // zero samples
            3; // END element
        byte[] frame = new byte[(int) ((bits + 7) / 8)];
        // element tag: SCE = 0, CPE = 1 (3 bits); instance tag and unused bits stay 0; no explicit
        // sample count; no shifted bits; escape flag = 1 (uncompressed)
        int tag = channels == 2 ? 1 : 0;
        setBits(frame, 0, 3, tag);
        setBits(frame, 3 + 4 + 12 + 1 + 2, 1, 1);
        setBits(frame, bits - 3, 3, 7); // END
        return frame;
    }

    private static void setBits(byte[] target, long position, int count, int value) {
        for (int i = 0; i < count; i++) {
            if (((value >> (count - 1 - i)) & 1) != 0) {
                long bit = position + i;
                target[(int) (bit >> 3)] |= (byte) (0x80 >> (bit & 7));
            }
        }
    }

    private long writeHeader(Sdp.AlacConfig config) throws IOException {
        this.framesPerPacket = config.frameLength();
        this.silentFrame = silentFrame(config);
        writeAscii("caff");
        writeShort(1);
        writeShort(0);

        // desc: audio stream description
        writeAscii("desc");
        writeLong(32);
        writeDouble(config.sampleRate());
        writeAscii("alac");
        writeInt(0); // format flags
        writeInt(0); // bytes per packet (variable)
        writeInt(config.frameLength());
        writeInt(config.channels());
        writeInt(config.bitDepth());

        // chan: standard stereo layout
        writeAscii("chan");
        writeLong(12);
        writeInt(0x00650002); // kAudioChannelLayoutTag_Stereo
        writeInt(0); // bitmap
        writeInt(0); // number of descriptions

        // kuki: ALAC magic cookie wrapped in frma/alac atoms (same structure as ffmpeg writes)
        writeAscii("kuki");
        writeLong(48);
        writeInt(12);
        writeAscii("frma");
        writeAscii("alac");
        writeInt(36);
        writeAscii("alac");
        writeInt(0); // version / flags
        file.write(config.magicCookie());

        // data: streamed frames; CAF requires a leading edit count, size patched in close()
        writeAscii("data");
        writeLong(0); // placeholder
        writeInt(0); // edit count
        return file.getFilePointer();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        long end = file.getFilePointer();
        long dataSize = end - dataPayloadStart + 4; // include edit count

        // Patch the data chunk size.
        file.seek(dataPayloadStart - 12);
        writeLong(dataSize);
        file.seek(end);

        // pakt: packet table, variable packet sizes.
        writeAscii("pakt");
        long paktStart = file.getFilePointer();
        writeLong(0); // placeholder size
        writeLong(packetSizes.size());
        writeLong(framesWritten);
        writeInt(0); // priming frames
        writeInt(0); // remainder frames
        ByteBuffer varint = ByteBuffer.allocate(5);
        for (int size : packetSizes) {
            varint.clear();
            int value = size;
            // 7 bits per byte, continuation bit on all but the last
            int groups = 1;
            for (int v = value >>> 7; v != 0; v >>>= 7) {
                groups++;
            }
            for (int i = groups - 1; i >= 0; i--) {
                int b = (value >>> (7 * i)) & 0x7F;
                if (i > 0) {
                    b |= 0x80;
                }
                varint.put((byte) b);
            }
            varint.flip();
            byte[] bytes = new byte[varint.remaining()];
            varint.get(bytes);
            file.write(bytes);
        }
        long paktEnd = file.getFilePointer();
        file.seek(paktStart);
        writeLong(paktEnd - paktStart - 8);
        file.seek(paktEnd);
        file.getFD().sync();
        file.close();
    }

    private void writeAscii(String value) throws IOException {
        file.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeShort(int value) throws IOException {
        file.write((value >> 8) & 0xFF);
        file.write(value & 0xFF);
    }

    private void writeInt(int value) throws IOException {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
        file.write(b);
    }

    private void writeLong(long value) throws IOException {
        byte[] b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
        file.write(b);
    }

    private void writeDouble(double value) throws IOException {
        byte[] b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array();
        file.write(b);
    }
}
