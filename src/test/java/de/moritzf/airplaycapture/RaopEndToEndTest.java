package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.media.Transcoder;
import de.moritzf.airplaycapture.raop.RaopCrypto;
import de.moritzf.airplaycapture.raop.RaopServer;
import de.moritzf.airplaycapture.recording.RecordingManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test: a simulated AirPlay sender drives the real RTSP handshake, AES-encrypted RTP
 * audio, metadata/artwork and verifies the exported files with ffprobe.
 */
class RaopEndToEndTest {

    @TempDir
    Path temp;

    @Test
    void recordsTwoTracksWithMetadataAndCover() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        Path source = TestMedia.createAlacCaf(temp, 2.0);
        TestMedia.CafData alac = TestMedia.readCaf(source);
        Path coverFile = TestMedia.createCover(temp);
        byte[] cover = Files.readAllBytes(coverFile);

        AppDirs dirs = new AppDirs(temp.resolve("data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        settings.deviceName = "Test Recorder";
        // Offline and deterministic: no tag completion or catalog checks against online services.
        settings.metadataEnrichmentEnabled = false;
        settings.audiobookLookupEnabled = false;
        settings.audiobookLookupMusicBrainz = false;
        settings.libraryDir = "library";
        settings.formats = new java.util.ArrayList<>(List.of("m4a"));
        settings.minTrackSeconds = 0;
        settings.idleFinalizeSeconds = 3;

        Library library = new Library(dirs, settings);
        UiState ui = new UiState();
        Transcoder transcoder = new Transcoder(settings);
        RecordingManager recorder = new RecordingManager(dirs, library, transcoder, ui, settings);
        RaopServer server = new RaopServer(settings, recorder, ui);
        server.start(false);

        byte[] aesKey = RaopCrypto.randomAesKey();
        byte[] aesIv = new byte[16];
        new SecureRandom().nextBytes(aesIv);

        try (FakeSender sender = new FakeSender("127.0.0.1", server.port())) {
            sender.options();
            sender.announce(TestMedia.fmtp(alac), aesKey, aesIv);
            sender.setup();
            sender.record();

            long rtp = 0;
            sender.metadata(
                Map.of(
                    "minm",
                    "Test Song One",
                    "asar",
                    "Unit Artist",
                    "asal",
                    "Test Album",
                    "asgn",
                    "Rock"
                )
            );
            sender.artwork(cover);
            sender.progress(rtp, rtp + 88200);
            rtp = sender.sendAudio(alac.packets(), aesKey, aesIv, rtp);

            Thread.sleep(400);
            sender.metadata(
                Map.of(
                    "minm",
                    "Test Song Two",
                    "asar",
                    "Unit Artist",
                    "asal",
                    "Test Album",
                    "asgn",
                    "Rock"
                )
            );
            sender.artwork(cover);
            sender.progress(rtp, rtp + 88200);
            sender.sendAudio(alac.packets(), aesKey, aesIv, rtp);

            Thread.sleep(500);
            sender.teardown();
        }

        List<de.moritzf.airplaycapture.library.LibraryTrack> tracks = waitForTracks(library, 2);
        server.stop();
        recorder.shutdown();

        assertEquals(2, tracks.size(), "expected two separately recorded tracks");
        for (var track : tracks) {
            assertEquals("Unit Artist", track.artist);
            assertEquals("Test Album", track.album);
            assertEquals(
                "ready",
                track.status,
                () -> "status " + track.status + " error " + track.error
            );
            assertTrue(track.files.containsKey("m4a"), "m4a missing: " + track.files);
            Path file = library.file(track, "m4a");
            assertNotNull(file);
            assertTrue(Files.exists(file), "file missing: " + file);
            assertTrue(track.durationMs > 1000, "duration too short: " + track.durationMs);

            String tags = TestMedia.ffprobe(
                file,
                "-show_entries",
                "format_tags=title,artist,album"
            );
            assertTrue(tags.contains("Test Song"), tags);
            assertTrue(tags.contains("Unit Artist"), tags);
            String streams = TestMedia.ffprobe(file, "-show_entries", "stream=codec_type");
            assertTrue(streams.contains("video"), "cover art not embedded: " + streams);
        }
        assertTrue(tracks.stream().anyMatch(t -> "Test Song One".equals(t.title)));
        assertTrue(tracks.stream().anyMatch(t -> "Test Song Two".equals(t.title)));
        assertFalse(dirs.tmpDir().toFile().listFiles().length > 0, "temp files left behind");
    }

    @Test
    void recoversResentPacketsAndFillsLostOnesWithSilence() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        TestMedia.CafData alac = TestMedia.readCaf(TestMedia.createAlacCaf(temp, 3.0));
        List<byte[]> packets = alac.packets();
        assertTrue(packets.size() >= 30, "need room for three gaps: " + packets.size());

        AppDirs dirs = new AppDirs(temp.resolve("lossy-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        settings.deviceName = "Test Recorder";
        // Offline and deterministic: no tag completion or catalog checks against online services.
        settings.metadataEnrichmentEnabled = false;
        settings.audiobookLookupEnabled = false;
        settings.audiobookLookupMusicBrainz = false;
        settings.formats = new java.util.ArrayList<>(List.of("m4a"));
        settings.minTrackSeconds = 0;
        settings.idleFinalizeSeconds = 3;
        settings.packetRecoveryMs = 500;

        Library library = new Library(dirs, settings);
        RecordingManager recorder = new RecordingManager(
            dirs,
            library,
            new Transcoder(settings),
            new UiState(),
            settings
        );
        RaopServer server = new RaopServer(settings, recorder, new UiState());
        server.start(false);

        byte[] aesKey = RaopCrypto.randomAesKey();
        byte[] aesIv = new byte[16];
        new SecureRandom().nextBytes(aesIv);

        // one lone packet and a run of three are resent on request; a run of four never is
        java.util.Set<Integer> resendable = java.util.Set.of(5, 10, 11, 12);
        java.util.Set<Integer> lost = java.util.Set.of(20, 21, 22, 23);
        try (FakeSender sender = new FakeSender("127.0.0.1", server.port())) {
            sender.options();
            sender.announce(TestMedia.fmtp(alac), aesKey, aesIv);
            sender.setup();
            sender.record();
            sender.metadata(
                Map.of("minm", "Lossy Song", "asar", "Unit Artist", "asal", "Test Album")
            );
            sender.answerResends(index -> !lost.contains(index));
            sender.sendAudio(
                packets,
                aesKey,
                aesIv,
                0,
                index -> resendable.contains(index) || lost.contains(index)
            );
            Thread.sleep(settings.packetRecoveryMs + 1000);
            sender.teardown();
        }

        var track = waitForTracks(library, 1).getFirst();
        server.stop();
        recorder.shutdown();

        assertEquals(
            "ready",
            track.status,
            () -> "status " + track.status + " error " + track.error
        );
        assertEquals(1, track.gaps, "only the run that was never resent is a gap");
        assertEquals(lost.size(), track.gapPackets);
        assertEquals(lost.size(), track.filledPackets, "the lost run is written as silence");
        assertEquals(2, track.recoveredGaps, "both resent holes are recovered");
        assertTrue(track.maxRecoveryMs < settings.packetRecoveryMs, "recovered within the window");

        long sentMs = Math.round(
            (packets.size() * (double) alac.frameLength() * 1000) / alac.sampleRate()
        );
        assertEquals(sentMs, track.durationMs, 30, "the recording keeps the stream's length");
        String probed = TestMedia.ffprobe(
            library.file(track, "m4a"),
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1"
        ).trim();
        assertEquals(sentMs / 1000.0, Double.parseDouble(probed), 0.15, "exported file length");
    }

    @Test
    void assemblesAudiobookChaptersIntoM4b() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        Path source = TestMedia.createAlacCaf(temp, 2.0);
        TestMedia.CafData alac = TestMedia.readCaf(source);
        byte[] cover = Files.readAllBytes(TestMedia.createCover(temp));

        AppDirs dirs = new AppDirs(temp.resolve("audiobook-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        settings.deviceName = "Test Recorder";
        // Offline and deterministic: no tag completion or catalog checks against online services.
        settings.metadataEnrichmentEnabled = false;
        settings.audiobookLookupEnabled = false;
        settings.audiobookLookupMusicBrainz = false;
        settings.formats = new java.util.ArrayList<>(List.of("m4a"));
        settings.minTrackSeconds = 0;
        settings.idleFinalizeSeconds = 3;
        settings.audiobookEnabled = true;
        settings.audiobookAutoAssemble = true;
        settings.audiobookGenres = "audiobook";
        settings.audiobookLongTrackSeconds = 0;
        settings.audiobookIdleSeconds = 60;
        settings.audiobookKeepChapters = true;

        Library library = new Library(dirs, settings);
        UiState ui = new UiState();
        RecordingManager recorder = new RecordingManager(
            dirs,
            library,
            new Transcoder(settings),
            ui,
            settings
        );
        RaopServer server = new RaopServer(settings, recorder, ui);
        server.start(false);

        byte[] aesKey = RaopCrypto.randomAesKey();
        byte[] aesIv = new byte[16];
        new SecureRandom().nextBytes(aesIv);

        try (FakeSender sender = new FakeSender("127.0.0.1", server.port())) {
            sender.options();
            sender.announce(TestMedia.fmtp(alac), aesKey, aesIv);
            sender.setup();
            sender.record();

            long rtp = 0;
            for (int chapter = 1; chapter <= 3; chapter++) {
                sender.metadata(
                    Map.of(
                        "minm",
                        "Chapter " + chapter,
                        "asar",
                        "Narrator " + chapter,
                        "asal",
                        "Test Book",
                        "asgn",
                        "Audiobook",
                        "astn",
                        String.valueOf(chapter)
                    )
                );
                sender.artwork(cover);
                sender.progress(rtp, rtp + 88200);
                rtp = sender.sendAudio(alac.packets(), aesKey, aesIv, rtp);
                Thread.sleep(300);
            }
            sender.teardown();
        }

        var book = waitForAudiobook(library);
        server.stop();
        recorder.shutdown();

        assertEquals("ready", book.status);
        assertEquals("Test Book", book.title);
        assertEquals("Narrator 1", book.artist);
        assertEquals(3, book.chapterCount);
        assertTrue(book.files.containsKey("m4b"), "m4b missing: " + book.files);
        Path file = library.file(book, "m4b");
        assertTrue(Files.exists(file));

        String chapters = TestMedia.ffprobe(
            file,
            "-show_chapters",
            "-show_entries",
            "chapter=start_time:chapter_tags=title",
            "-of",
            "json"
        );
        for (int chapter = 1; chapter <= 3; chapter++) {
            assertTrue(chapters.contains("Chapter " + chapter), chapters);
        }
        String tags = TestMedia.ffprobe(
            file,
            "-show_entries",
            "format=duration:format_tags=title,artist,genre"
        );
        assertTrue(tags.contains("Test Book"), tags);
        assertTrue(tags.contains("Audiobook"), tags);
        assertTrue(
            TestMedia.ffprobe(file, "-show_entries", "stream=codec_type").contains("video"),
            "cover art not embedded"
        );
    }

    private static de.moritzf.airplaycapture.library.LibraryTrack waitForAudiobook(Library library)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            for (var track : library.tracks()) {
                if (track.files.containsKey("m4b") && "ready".equals(track.status)) {
                    return track;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("audiobook not assembled: " + library.tracks());
    }

    private static List<de.moritzf.airplaycapture.library.LibraryTrack> waitForTracks(
        Library library,
        int expected
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            List<de.moritzf.airplaycapture.library.LibraryTrack> tracks = library.tracks();
            if (
                tracks.size() >= expected &&
                tracks.stream().noneMatch(t -> "processing".equals(t.status))
            ) {
                return tracks;
            }
            Thread.sleep(200);
        }
        List<de.moritzf.airplaycapture.library.LibraryTrack> tracks = library.tracks();
        throw new AssertionError("tracks did not finish: " + tracks.size() + " / " + tracks);
    }

    /** Minimal AirPlay sender speaking the same RTSP/RTP dialect as iTunes/macOS. */
    private static final class FakeSender implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final DatagramSocket control;
        private final DatagramSocket timing;
        private final InetAddress host;
        private int cseq = 1;
        private int sequence = 1;
        private int audioPort;
        /** Every audio packet sent, by RTP sequence, as a sender's retransmit history. */
        private final Map<Integer, byte[]> history = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Integer, Integer> indexOf =
            new java.util.concurrent.ConcurrentHashMap<>();
        private Thread resender;

        FakeSender(String address, int port) throws IOException {
            this.host = InetAddress.getByName(address);
            this.socket = new Socket(host, port);
            this.socket.setSoTimeout(15_000);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.control = new DatagramSocket(0, host);
            this.timing = new DatagramSocket(0, host);
        }

        void options() throws IOException {
            Response response = request(
                "OPTIONS",
                "*",
                Map.of(
                    "Apple-Challenge",
                    Base64.getEncoder().encodeToString("0123456789abcdef".getBytes())
                ),
                null
            );
            assertEquals(200, response.code);
            assertTrue(response.headers.containsKey("apple-response"), "no Apple-Response");
        }

        void announce(String fmtp, byte[] aesKey, byte[] aesIv) throws Exception {
            String sdp =
                "v=0\r\n" +
                "o=iTunes 1234567890 0 IN IP4 127.0.0.1\r\n" +
                "s=iTunes\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 AppleLossless\r\n" +
                "a=fmtp:" +
                fmtp +
                "\r\n" +
                "a=rsaaeskey:" +
                Base64.getEncoder().encodeToString(RaopCrypto.encryptSessionKey(aesKey)) +
                "\r\n" +
                "a=aesiv:" +
                Base64.getEncoder().encodeToString(aesIv) +
                "\r\n";
            Response response = request(
                "ANNOUNCE",
                "rtsp://127.0.0.1/1234",
                Map.of("Content-Type", "application/sdp"),
                sdp.getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(200, response.code);
        }

        void setup() throws IOException {
            String transport =
                "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=" +
                control.getLocalPort() +
                ";timing_port=" +
                timing.getLocalPort();
            Response response = request(
                "SETUP",
                "rtsp://127.0.0.1/1234",
                Map.of("Transport", transport),
                null
            );
            assertEquals(200, response.code, response.headers.toString());
            String reply = response.headers.get("transport");
            assertNotNull(reply);
            audioPort = Integer.parseInt(extract(reply, "server_port="));
            assertTrue(audioPort > 0);
            assertEquals("1", response.headers.get("session"));
        }

        void record() throws IOException {
            assertEquals(200, request("RECORD", "rtsp://127.0.0.1/1234", Map.of(), null).code);
        }

        void metadata(Map<String, String> tags) throws IOException {
            assertEquals(
                200,
                request(
                    "SET_PARAMETER",
                    "rtsp://127.0.0.1/1234",
                    Map.of("Content-Type", "application/x-dmap-tagged"),
                    dmap(tags)
                ).code
            );
        }

        void artwork(byte[] image) throws IOException {
            assertEquals(
                200,
                request(
                    "SET_PARAMETER",
                    "rtsp://127.0.0.1/1234",
                    Map.of("Content-Type", "image/jpeg"),
                    image
                ).code
            );
        }

        void progress(long start, long end) throws IOException {
            String body = "progress: " + start + "/" + start + "/" + end + "\r\n";
            assertEquals(
                200,
                request(
                    "SET_PARAMETER",
                    "rtsp://127.0.0.1/1234",
                    Map.of("Content-Type", "text/parameters"),
                    body.getBytes(StandardCharsets.UTF_8)
                ).code
            );
        }

        long sendAudio(List<byte[]> frames, byte[] key, byte[] iv, long rtp) throws Exception {
            return sendAudio(frames, key, iv, rtp, index -> false);
        }

        /** Sends audio, dropping the packets {@code drop} selects as a lossy network would. */
        long sendAudio(
            List<byte[]> frames,
            byte[] key,
            byte[] iv,
            long rtp,
            java.util.function.IntPredicate drop
        ) throws Exception {
            try (DatagramSocket audio = new DatagramSocket(0, host)) {
                for (int index = 0; index < frames.size(); index++) {
                    byte[] frame = frames.get(index);
                    byte[] payload = encrypt(key, iv, frame);
                    ByteBuffer packet = ByteBuffer.allocate(12 + payload.length);
                    packet.put((byte) 0x80);
                    packet.put((byte) 0x60);
                    packet.putShort((short) sequence);
                    packet.putInt((int) rtp);
                    packet.putInt(0x11223344);
                    packet.put(payload);
                    history.put(sequence, packet.array());
                    indexOf.put(sequence, index);
                    if (!drop.test(index)) {
                        audio.send(
                            new DatagramPacket(packet.array(), packet.capacity(), host, audioPort)
                        );
                    }
                    sequence = (sequence + 1) & 0xFFFF;
                    rtp += 4096;
                    Thread.sleep(2);
                }
            }
            return rtp;
        }

        /**
         * Answers the receiver's 0xD5 retransmit requests on the control socket with 0x56 packets,
         * for the packets (by send index) that {@code available} says are still in the history.
         */
        void answerResends(java.util.function.IntPredicate available) {
            resender = Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[64];
                while (!control.isClosed()) {
                    try {
                        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                        control.receive(request);
                        if (request.getLength() < 8 || (buffer[1] & 0x7F) != 0x55) {
                            continue;
                        }
                        int first = ((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF);
                        int count = ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
                        for (int i = 0; i < count; i++) {
                            int seq = (first + i) & 0xFFFF;
                            byte[] original = history.get(seq);
                            Integer index = indexOf.get(seq);
                            if (original == null || !available.test(index)) {
                                continue;
                            }
                            byte[] wrapped = new byte[4 + original.length];
                            wrapped[0] = (byte) 0x80;
                            wrapped[1] = (byte) 0xD6;
                            wrapped[2] = 0;
                            wrapped[3] = 1;
                            System.arraycopy(original, 0, wrapped, 4, original.length);
                            control.send(
                                new DatagramPacket(
                                    wrapped,
                                    wrapped.length,
                                    request.getAddress(),
                                    request.getPort()
                                )
                            );
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        void teardown() throws IOException {
            assertEquals(200, request("TEARDOWN", "rtsp://127.0.0.1/1234", Map.of(), null).code);
        }

        private Response request(
            String method,
            String uri,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
            request.append("CSeq: ").append(cseq++).append("\r\n");
            request.append("User-Agent: iTunes/12.5\r\n");
            headers.forEach((key, value) ->
                request.append(key).append(": ").append(value).append("\r\n")
            );
            if (body != null) {
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                out.write(body);
            }
            out.flush();

            String status = readLine();
            if (status == null) {
                throw new IOException("connection closed");
            }
            String[] parts = status.split(" ");
            int code = Integer.parseInt(parts[1]);
            Map<String, String> responseHeaders = new HashMap<>();
            String line;
            while ((line = readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    responseHeaders.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim()
                    );
                }
            }
            int length = Integer.parseInt(responseHeaders.getOrDefault("content-length", "0"));
            byte[] responseBody = in.readNBytes(length);
            return new Response(code, responseHeaders, responseBody);
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    buffer.write(b);
                }
            }
            return b == -1 && buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
        }

        private static byte[] encrypt(byte[] key, byte[] iv, byte[] plain) throws Exception {
            int aligned = plain.length & ~0xF;
            byte[] out = plain.clone();
            if (aligned > 0) {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv)
                );
                cipher.doFinal(plain, 0, aligned, out, 0);
            }
            return out;
        }

        private static byte[] dmap(Map<String, String> tags) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // container header so the parser has to descend into it
            ByteArrayOutputStream items = new ByteArrayOutputStream();
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(tags).entrySet()) {
                byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                items.write(entry.getKey().getBytes(StandardCharsets.US_ASCII));
                items.write(ByteBuffer.allocate(4).putInt(value.length).array());
                items.write(value);
            }
            byte[] content = items.toByteArray();
            out.write("mlit".getBytes(StandardCharsets.US_ASCII));
            out.write(ByteBuffer.allocate(4).putInt(content.length).array());
            out.write(content);
            return out.toByteArray();
        }

        private static String extract(String transport, String key) {
            int index = transport.indexOf(key);
            if (index < 0) {
                return null;
            }
            int start = index + key.length();
            int end = start;
            while (end < transport.length() && Character.isDigit(transport.charAt(end))) {
                end++;
            }
            return transport.substring(start, end);
        }

        @Override
        public void close() throws IOException {
            control.close();
            if (resender != null) {
                resender.interrupt();
            }
            timing.close();
            socket.close();
        }
    }

    private record Response(int code, Map<String, String> headers, byte[] body) {}
}
