package de.moritzf.airplaycapture.raop;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.media.AlacConfig;
import de.moritzf.airplaycapture.recording.RecordingManager;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP side of a RAOP stream: audio, control and timing sockets, packet reordering, retransmit
 * requests and delivery of ALAC frames to the recorder.
 */
final class StreamSession {

    private static final int RTP_HEADER = 12;
    private static final long RESEND_INTERVAL_MS = 150;
    /** RAOP resend requests carry one 16-bit range; keep the batch at 64 like AirPlay senders. */
    private static final int RESEND_BATCH = 64;
    /** Reorder buffer ceiling; also bounds how far one broken stream may stall the pipeline. */
    private static final int REORDER_LIMIT = 4096;
    /** Longest loss filled with silence (~60 s); longer jumps are not packet loss. */
    private static final int MAX_SILENCE_PACKETS = 7500;

    private record RawPacket(byte[] data, int length, String origin) {}

    private static final class Frame {

        final long sequence;
        final long rtpTime;
        final byte[] payload;
        final int length;

        Frame(long sequence, long rtpTime, byte[] payload, int length) {
            this.sequence = sequence;
            this.rtpTime = rtpTime;
            this.payload = payload;
            this.length = length;
        }
    }

    private final InetAddress clientAddress;
    private final InetAddress localAddress;
    private final AlacConfig config;
    private final byte[] aesKey;
    private final byte[] aesIv;
    private final boolean encrypted;
    private final RecordingManager recorder;
    private final Settings settings;
    private final String label;

    private final DatagramSocket audioSocket;
    private final DatagramSocket controlSocket;
    private final DatagramSocket timingSocket;
    private final InetSocketAddress remoteControl;
    private final InetSocketAddress remoteTiming;

    private final ArrayBlockingQueue<RawPacket> incoming = new ArrayBlockingQueue<>(4096);
    private final TreeMap<Long, Frame> reorder = new TreeMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Thread> threads = new ArrayList<>();

    private long nextSequence = -1;
    private long gapSince;
    /** First sequence after the hole being waited for; the hole is closed once delivery passes it. */
    private long gapUntil;
    private long lastResendAt;
    private boolean gapRequestLogged;
    private long deliveredFrames;
    private long lastRtpTime = -1;
    private int lostPackets;

    StreamSession(
        InetAddress clientAddress,
        InetAddress localAddress,
        Sdp sdp,
        int remoteControlPort,
        int remoteTimingPort,
        RecordingManager recorder,
        Settings settings
    ) throws IOException {
        this.clientAddress = clientAddress;
        this.localAddress = localAddress;
        this.config = sdp.alac();
        this.recorder = recorder;
        this.settings = settings;
        this.label = "raop/" + clientAddress.getHostAddress();
        byte[] key = null;
        byte[] iv = null;
        if (sdp.isEncrypted()) {
            try {
                key = RaopCrypto.decryptSessionKey(sdp.encryptedAesKey());
                iv = sdp.aesIv();
            } catch (Exception e) {
                Log.error("raop", "cannot decrypt session key: " + e.getMessage());
            }
        }
        this.aesKey = key;
        this.aesIv = iv;
        this.encrypted = key != null && iv != null;
        this.remoteControl = new InetSocketAddress(clientAddress, remoteControlPort);
        this.remoteTiming = new InetSocketAddress(clientAddress, remoteTimingPort);

        this.audioSocket = new DatagramSocket(0, localAddress);
        this.controlSocket = new DatagramSocket(0, localAddress);
        this.timingSocket = new DatagramSocket(0, localAddress);
        // A larger receive buffer absorbs sender bursts while the exporter is busy with disk I/O.
        audioSocket.setReceiveBufferSize(1 << 20);
        controlSocket.setReceiveBufferSize(1 << 19);
        for (DatagramSocket socket : List.of(audioSocket, controlSocket, timingSocket)) {
            socket.setSoTimeout(200);
        }
        Log.info(
            "raop",
            "stream sockets: audio " +
                audioSocket.getLocalPort() +
                ", control " +
                controlSocket.getLocalPort() +
                ", timing " +
                timingSocket.getLocalPort() +
                " (audio buffer " +
                audioSocket.getReceiveBufferSize() / 1024 +
                " KiB" +
                (encrypted ? ", AES-128)" : ", unencrypted)")
        );
    }

    int audioPort() {
        return audioSocket.getLocalPort();
    }

    int controlPort() {
        return controlSocket.getLocalPort();
    }

    int timingPort() {
        return timingSocket.getLocalPort();
    }

    void start() {
        threads.add(thread("audio-rx", this::receiveAudio));
        threads.add(thread("control-rx", this::receiveControl));
        threads.add(thread("timing-rx", this::receiveTiming));
        threads.add(thread("audio-pipeline", this::pipeline));
        threads.forEach(Thread::start);
    }

    private Thread thread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void receiveAudio() {
        byte[] buffer = new byte[2048];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                audioSocket.receive(packet);
                byte[] copy = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
                if (
                    !incoming.offer(
                        new RawPacket(copy, copy.length, "audio"),
                        100,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    Log.warn(label, "audio queue full, dropping packet");
                }
            } catch (SocketTimeoutException ignored) {
                // periodic wakeup
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Log.warn(label, "audio receive error: " + e.getMessage());
                }
            }
        }
    }

    private void receiveControl() {
        byte[] buffer = new byte[2048];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                controlSocket.receive(packet);
                int type = packet.getData()[1] & 0x7F;
                if (type == 0x56) {
                    // retransmitted audio packet: 4 byte wrapper + normal RTP packet
                    byte[] copy = new byte[packet.getLength() - 4];
                    System.arraycopy(packet.getData(), 4, copy, 0, copy.length);
                    incoming.offer(new RawPacket(copy, copy.length, "control"));
                } else if (type == 0x54) {
                    // client sync packet - used for latency, irrelevant for recording
                    Log.info(label, "client sync packet");
                }
            } catch (SocketTimeoutException ignored) {
                // periodic wakeup
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Log.warn(label, "control receive error: " + e.getMessage());
                }
            }
        }
    }

    private void receiveTiming() {
        byte[] buffer = new byte[64];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                timingSocket.receive(packet);
            } catch (SocketTimeoutException ignored) {
                // periodic wakeup
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Log.warn(label, "timing receive error: " + e.getMessage());
                }
            }
        }
    }

    private void pipeline() {
        while (running.get()) {
            RawPacket packet;
            try {
                packet = incoming.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (packet != null) {
                process(packet);
            }
            flushReorderBuffer();
        }
    }

    private void process(RawPacket packet) {
        byte[] data = packet.data;
        if (data.length < RTP_HEADER) {
            return;
        }
        int type = data[1] & 0x7F;
        if (type != 0x60) {
            Log.info(label, "unexpected packet type 0x" + Integer.toHexString(type));
            return;
        }
        int sequence = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long rtpTime =
            ((long) (data[4] & 0xFF) << 24) |
            ((data[5] & 0xFF) << 16) |
            ((data[6] & 0xFF) << 8) |
            (data[7] & 0xFF);
        int payloadOffset = RTP_HEADER;
        int payloadLength = data.length - RTP_HEADER;
        if (payloadLength <= 0) {
            return;
        }
        if (encrypted) {
            try {
                int decrypted = RaopCrypto.decryptPayload(
                    aesKey,
                    aesIv,
                    data,
                    payloadOffset,
                    payloadLength
                );
                if (decrypted == 0) {
                    Log.warn(label, "payload too small to decrypt: " + payloadLength);
                }
            } catch (Exception e) {
                Log.error(label, "AES decryption failed: " + e.getMessage());
                return;
            }
        }
        long extended = extend(sequence);
        if (extended < nextSequence) {
            return; // duplicate or too late
        }
        if (reorder.containsKey(extended)) {
            return;
        }
        reorder.put(extended, new Frame(extended, rtpTime, data, payloadLength));
        if (reorder.size() > REORDER_LIMIT) {
            // pathologically broken stream: skip ahead instead of growing without bound
            Long lowest = reorder.firstKey();
            Log.warn(label, "reorder buffer overflow, skipping to sequence " + lowest);
            int skipped = (int) Math.max(1, lowest - nextSequence);
            nextSequence = lowest;
            lostPackets++;
            recorder.noteGap(skipped, fillable(skipped, reorder.get(lowest).rtpTime));
            gapSince = 0;
            gapRequestLogged = false;
            recorder.onGapPending(false);
            flushReorderBuffer();
        }
    }

    private long extend(int sequence) {
        if (nextSequence < 0) {
            return sequence;
        }
        long base = nextSequence & ~0xFFFFL;
        long candidate = base | (sequence & 0xFFFFL);
        while (candidate < nextSequence - 32768) {
            candidate += 65536;
        }
        while (candidate > nextSequence + 32768) {
            candidate -= 65536;
        }
        return candidate;
    }

    private void flushReorderBuffer() {
        if (nextSequence < 0 && !reorder.isEmpty()) {
            nextSequence = reorder.firstKey();
        }
        long now = System.currentTimeMillis();
        while (true) {
            Frame frame = reorder.remove(nextSequence);
            if (frame == null) {
                break;
            }
            deliver(frame);
            nextSequence++;
        }
        if (gapSince != 0 && nextSequence >= gapUntil) {
            // every packet of the hole arrived (resent or late) within the recovery window; a
            // later hole gets a window of its own
            recorder.noteRecovered(now - gapSince);
            recorder.onGapPending(false);
            gapSince = 0;
            gapRequestLogged = false;
        }
        if (reorder.isEmpty()) {
            gapSince = 0;
            gapRequestLogged = false;
            return;
        }
        long lowest = reorder.firstKey();
        long missing = lowest - nextSequence;
        if (missing > 0) {
            if (gapSince == 0) {
                gapSince = now;
                gapUntil = lowest;
                recorder.onGapPending(true);
            }
            if (now - lastResendAt > RESEND_INTERVAL_MS) {
                // Ask for every outstanding range, not only the first 64 packets. AirPlay
                // senders keep only a short retransmit window, so repeated requests matter.
                long requested = Math.min(missing, 512);
                for (long offset = 0; offset < requested; offset += RESEND_BATCH) {
                    requestResend(
                        nextSequence + offset,
                        (int) Math.min(RESEND_BATCH, requested - offset)
                    );
                }
                if (!gapRequestLogged) {
                    Log.info(
                        label,
                        "waiting up to " +
                            settings.packetRecoveryMs +
                            " ms for " +
                            missing +
                            " missing packet(s) at " +
                            nextSequence
                    );
                    gapRequestLogged = true;
                }
                lastResendAt = now;
            }
            if (now - gapSince > settings.packetRecoveryMs) {
                Log.warn(label, "lost " + missing + " audio packet(s), continuing at " + lowest);
                nextSequence = lowest;
                lostPackets++;
                recorder.noteGap((int) missing, fillable(missing, reorder.get(lowest).rtpTime));
                gapSince = 0;
                gapRequestLogged = false;
                recorder.onGapPending(false);
                flushReorderBuffer();
            }
        }
    }

    private void deliver(Frame frame) {
        recorder.onAudio(
            frame.rtpTime,
            frame.payload,
            RTP_HEADER,
            frame.length,
            config.frameLength()
        );
        deliveredFrames++;
        lastRtpTime = frame.rtpTime;
    }

    /**
     * True when {@code missing} packets are a real hole in a continuous stream: the RTP clock of
     * the next packet advanced by exactly those frames. Anything else (a sender-side jump, a
     * restart) is spliced as before rather than padded with silence of a guessed length.
     */
    private boolean fillable(long missing, long nextRtpTime) {
        if (lastRtpTime < 0 || missing <= 0 || missing > MAX_SILENCE_PACKETS) {
            return false;
        }
        long advanced = (nextRtpTime - lastRtpTime) & 0xFFFFFFFFL;
        return advanced == (missing + 1) * (long) config.frameLength();
    }

    private void requestResend(long first, int count) {
        byte[] request = new byte[8];
        request[0] = (byte) 0x80;
        request[1] = (byte) 0xD5;
        request[2] = 0;
        request[3] = 1;
        request[4] = (byte) ((first >> 8) & 0xFF);
        request[5] = (byte) (first & 0xFF);
        request[6] = (byte) ((count >> 8) & 0xFF);
        request[7] = (byte) (count & 0xFF);
        try {
            controlSocket.send(new DatagramPacket(request, request.length, remoteControl));
        } catch (IOException e) {
            Log.warn(label, "cannot send resend request: " + e.getMessage());
        }
    }

    /** Sends the AirPlay timing request (0xD2). Replies are recorded but not needed for capture. */
    void sendTimingRequest() {
        byte[] request = new byte[32];
        request[0] = (byte) 0x80;
        request[1] = (byte) 0xD2;
        request[2] = 0x00;
        request[3] = 0x07;
        try {
            timingSocket.send(new DatagramPacket(request, request.length, remoteTiming));
        } catch (IOException e) {
            Log.warn(label, "cannot send timing request: " + e.getMessage());
        }
    }

    long deliveredFrames() {
        return deliveredFrames;
    }

    int lostPackets() {
        return lostPackets;
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        recorder.onGapPending(false);
        for (DatagramSocket socket : List.of(audioSocket, controlSocket, timingSocket)) {
            socket.close();
        }
        for (Thread thread : threads) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info(label, "stream closed: " + deliveredFrames + " frames, " + lostPackets + " lost");
    }
}
