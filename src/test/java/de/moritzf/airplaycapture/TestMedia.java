package de.moritzf.airplaycapture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Test helpers: ffmpeg-generated ALAC packets and a tiny CAF reader. */
final class TestMedia {

    record CafData(int frameLength, int compatibleVersion, int bitDepth, int pb, int mb, int kb,
                   int channels, int maxRun, int maxFrameBytes, int avgBitRate, int sampleRate,
                   List<byte[]> packets) {
    }

    private TestMedia() {
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static Path createAlacCaf(Path directory, double seconds) throws Exception {
        Path out = directory.resolve("source-" + System.nanoTime() + ".caf");
        List<String> command = List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds + ":sample_rate=44100",
                "-ac", "2", "-c:a", "alac", out.toString());
        run(command);
        return out;
    }

    static Path createCover(Path directory) throws Exception {
        Path out = directory.resolve("cover-" + System.nanoTime() + ".jpg");
        List<String> command = List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=96x96", "-frames:v", "1", out.toString());
        run(command);
        return out;
    }

    static CafData readCaf(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        expect(new String(data, 0, 4, StandardCharsets.US_ASCII), "caff");
        int position = 8;
        int frameLength = 0;
        int channels = 0;
        int bitDepth = 0;
        int sampleRate = 0;
        List<Integer> packetSizes = new ArrayList<>();
        byte[] payloadAtData = null;
        int editCount = 0;
        while (position + 12 <= data.length) {
            String tag = new String(data, position, 4, StandardCharsets.US_ASCII);
            long size = ByteBuffer.wrap(data, position + 4, 8).getLong();
            int payload = position + 12;
            if (size < 0 || payload + size > data.length) {
                break;
            }
            switch (tag) {
                case "desc" -> {
                    sampleRate = (int) Math.round(ByteBuffer.wrap(data, payload, 8).getDouble());
                    // format id (4), flags (4), bytesPerPacket (4), framesPerPacket (4)
                    frameLength = ByteBuffer.wrap(data, payload + 20, 4).getInt();
                    channels = ByteBuffer.wrap(data, payload + 24, 4).getInt();
                    bitDepth = ByteBuffer.wrap(data, payload + 28, 4).getInt();
                }
                case "kuki" -> {
                    int cookie = payload + 24; // frma/alac atoms + alac atom header + version
                    frameLength = ByteBuffer.wrap(data, cookie, 4).getInt();
                    if (bitDepth == 0) {
                        bitDepth = data[cookie + 5] & 0xFF;
                        channels = data[cookie + 9] & 0xFF;
                        sampleRate = ByteBuffer.wrap(data, cookie + 20, 4).getInt();
                    }
                }
                case "data" -> {
                    payloadAtData = new byte[(int) size];
                    System.arraycopy(data, payload, payloadAtData, 0, (int) size);
                    editCount = ByteBuffer.wrap(data, payload, 4).getInt();
                }
                case "pakt" -> {
                    int count = (int) ByteBuffer.wrap(data, payload, 8).getLong();
                    int cursor = payload + 24;
                    for (int i = 0; i < count && cursor < payload + size; i++) {
                        int value = 0;
                        int b;
                        do {
                            b = data[cursor++] & 0xFF;
                            value = (value << 7) | (b & 0x7F);
                        } while ((b & 0x80) != 0);
                        packetSizes.add(value);
                    }
                }
                default -> {
                }
            }
            position = payload + (int) size;
        }
        if (payloadAtData == null) {
            throw new IOException("no data chunk in " + path);
        }
        int offset = 0;
        int sum = packetSizes.stream().mapToInt(Integer::intValue).sum();
        if (sum + 4 == payloadAtData.length) {
            offset = 4;
        }
        List<byte[]> packets = new ArrayList<>();
        for (int size : packetSizes) {
            byte[] packet = new byte[size];
            System.arraycopy(payloadAtData, offset, packet, 0, size);
            offset += size;
            packets.add(packet);
        }
        return new CafData(frameLength, 0, bitDepth, 40, 10, 14, channels, 255, 0, 0, sampleRate, packets);
    }

    static String fmtp(CafData data) {
        return "96 " + data.frameLength() + " 0 " + data.bitDepth() + " 40 10 14 "
                + data.channels() + " 255 0 0 " + data.sampleRate();
    }

    static void run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    static String ffprobe(Path file, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("ffprobe", "-hide_banner", "-v", "error"));
        command.addAll(List.of(args));
        command.add(file.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(60, TimeUnit.SECONDS);
        return output;
    }

    private static void expect(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException("expected " + expected + " but got " + actual);
        }
    }
}
