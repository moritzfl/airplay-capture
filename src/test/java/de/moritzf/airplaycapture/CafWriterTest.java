package de.moritzf.airplaycapture;

import de.moritzf.airplaycapture.media.CafWriter;
import de.moritzf.airplaycapture.raop.Sdp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CafWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesCafThatFfmpegDecodes() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        Path source = TestMedia.createAlacCaf(temp, 2.5);
        TestMedia.CafData alac = TestMedia.readCaf(source);
        assertTrue(alac.packets().size() > 10, "expected ALAC packets");

        Sdp.AlacConfig config = new Sdp.AlacConfig(alac.frameLength(), 0, alac.bitDepth(), 40, 10, 14,
                alac.channels(), 255, 0, 0, alac.sampleRate());
        Path out = temp.resolve("recorded.caf");
        try (CafWriter writer = new CafWriter(out, config)) {
            for (byte[] packet : alac.packets()) {
                writer.writeFrame(packet, 0, packet.length);
            }
            assertEquals((long) alac.packets().size() * alac.frameLength(), writer.framesWritten());
        }

        assertTrue(Files.size(out) > 1000);
        String duration = TestMedia.ffprobe(out, "-show_entries", "format=duration");
        double seconds = Double.parseDouble(duration.replaceAll("(?s).*duration=([0-9.]+).*", "$1"));
        assertTrue(seconds > 2.4 && seconds < 2.7, "unexpected duration: " + duration);

        Path wav = temp.resolve("decoded.wav");
        TestMedia.run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", out.toString(), "-f", "wav", wav.toString()));
        assertTrue(Files.size(wav) > 100_000);
    }

    @Test
    void silenceFillsAGapWithoutBreakingTheStream() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        TestMedia.CafData alac = TestMedia.readCaf(TestMedia.createAlacCaf(temp, 2.0));
        Sdp.AlacConfig config = config(alac.frameLength(), alac.channels(), alac.sampleRate());
        List<byte[]> packets = alac.packets();
        int half = packets.size() / 2;
        int filled = 5;
        Path out = temp.resolve("gap.caf");
        try (CafWriter writer = new CafWriter(out, config)) {
            for (byte[] packet : packets.subList(0, half)) {
                writer.writeFrame(packet, 0, packet.length);
            }
            assertTrue(writer.writeSilence(filled));
            for (byte[] packet : packets.subList(half, packets.size())) {
                writer.writeFrame(packet, 0, packet.length);
            }
        }

        assertEquals("", decodeErrors(out), "ffmpeg must decode every frame");
        // compare against the same packets written without the gap, so container details such
        // as the partial last packet cancel out
        Path plain = temp.resolve("plain.caf");
        try (CafWriter writer = new CafWriter(plain, config)) {
            for (byte[] packet : packets) {
                writer.writeFrame(packet, 0, packet.length);
            }
        }
        double expected = duration(plain) + filled * (double) alac.frameLength() / alac.sampleRate();
        assertEquals(expected, duration(out), 0.01, "the gap keeps its place in time");

        double gapStart = half * (double) alac.frameLength() / alac.sampleRate();
        double gapEnd = (half + filled) * (double) alac.frameLength() / alac.sampleRate();
        assertTrue(maxVolume(out, gapStart + 0.01, gapEnd - 0.01) < -90, "filled span is silent");
        assertTrue(maxVolume(out, 0.1, gapStart - 0.1) > -30, "audio before the gap is intact");
        assertTrue(maxVolume(out, gapEnd + 0.1, expected - 0.1) > -30, "audio after the gap is intact");
    }

    @Test
    void silentFramesMatchAirPlayAndMonoLayouts() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        for (int channels : new int[] {2, 1}) {
            // AirPlay senders use 352-sample frames; ffmpeg's encoder cannot produce those.
            Sdp.AlacConfig config = config(352, channels, 44100);
            Path out = temp.resolve("silence-" + channels + ".caf");
            try (CafWriter writer = new CafWriter(out, config)) {
                assertTrue(writer.writeSilence(250));
            }
            assertEquals("", decodeErrors(out), channels + " channel(s)");
            assertEquals(250 * 352 / 44100.0, duration(out), 0.01, channels + " channel(s)");
            assertTrue(maxVolume(out, 0, 1.9) < -90, channels + " channel(s) silent");
        }
        assertEquals(null, CafWriter.silentFrame(config(352, 6, 44100)), "no silence for 5.1");
    }

    private static Sdp.AlacConfig config(int frameLength, int channels, int sampleRate) {
        return new Sdp.AlacConfig(frameLength, 0, 16, 40, 10, 14, channels, 255, 0, 0, sampleRate);
    }

    private static String decodeErrors(Path file) throws Exception {
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-v", "error",
                "-i", file.toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();
        return output;
    }

    private static double duration(Path file) throws Exception {
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", file.toString(),
                "-f", "null", "-").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        java.util.regex.Matcher time = java.util.regex.Pattern
                .compile("time=(\\d+):(\\d+):([0-9.]+)").matcher(output);
        double seconds = -1;
        while (time.find()) {
            seconds = Integer.parseInt(time.group(1)) * 3600 + Integer.parseInt(time.group(2)) * 60
                    + Double.parseDouble(time.group(3));
        }
        return seconds;
    }

    private static double maxVolume(Path file, double from, double to) throws Exception {
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", file.toString(),
                "-af", "atrim=start=" + from + ":end=" + to + ",volumedetect", "-f", "null", "-")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        java.util.regex.Matcher max = java.util.regex.Pattern
                .compile("max_volume: (-?inf|-?[0-9.]+) dB").matcher(output);
        if (!max.find()) {
            throw new AssertionError("no volumedetect output: " + output);
        }
        return max.group(1).endsWith("inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(max.group(1));
    }
}
