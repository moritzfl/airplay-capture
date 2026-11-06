package de.moritzf.airplaycapture.media;

import java.io.ByteArrayOutputStream;

/**
 * ALAC stream parameters (the ALACSpecificConfig cookie). AirPlay senders announce them in the SDP
 * {@code a=fmtp} line.
 */
public record AlacConfig(
    int frameLength,
    int compatibleVersion,
    int bitDepth,
    int pb,
    int mb,
    int kb,
    int channels,
    int maxRun,
    int maxFrameBytes,
    int avgBitRate,
    int sampleRate
) {
    public byte[] magicCookie() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(24);
        writeInt(out, frameLength);
        out.write(compatibleVersion);
        out.write(bitDepth);
        out.write(pb);
        out.write(mb);
        out.write(kb);
        out.write(channels);
        out.write((maxRun >> 8) & 0xFF);
        out.write(maxRun & 0xFF);
        writeInt(out, maxFrameBytes);
        writeInt(out, avgBitRate);
        writeInt(out, sampleRate);
        return out.toByteArray();
    }

    public int bytesPerFrame() {
        return (bitDepth / 8) * channels;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
