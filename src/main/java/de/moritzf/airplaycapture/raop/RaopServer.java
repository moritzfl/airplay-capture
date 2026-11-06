package de.moritzf.airplaycapture.raop;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.recording.RecordingManager;
import de.moritzf.airplaycapture.util.NetworkTools;
import de.moritzf.airplaycapture.util.NetworkTools.InterfaceInfo;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Legacy AirPlay ("RAOP") receiver: advertises {@code _raop._tcp} via mDNS and accepts the RTSP
 * control connection on the advertised port.
 */
public final class RaopServer {

    private final RecordingManager recorder;
    private final UiState ui;
    private final byte[] deviceId;
    private volatile Settings settings;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private JmDNS jmds;
    private InterfaceInfo networkInterface;

    private volatile StreamSession session;
    private volatile InetAddress sessionClient;
    private volatile int port;

    /** A receiver with a fresh, unstored device ID, for tests and one-off runs. */
    public RaopServer(Settings settings, RecordingManager recorder, UiState ui) {
        this(settings, recorder, ui, ReceiverId.generate(new java.security.SecureRandom()));
    }

    /** @param deviceId the six-byte AirPlay device ID, see {@link ReceiverId} */
    public RaopServer(Settings settings, RecordingManager recorder, UiState ui, byte[] deviceId) {
        this.settings = settings;
        this.recorder = recorder;
        this.ui = ui;
        this.deviceId = deviceId.clone();
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
    }

    public synchronized void start() throws IOException {
        start(true);
    }

    /**
     * @param advertise whether to announce the receiver over mDNS; without it, senders can only
     *                  connect to the port directly (tests)
     */
    public synchronized void start(boolean advertise) throws IOException {
        if (running) {
            return;
        }
        networkInterface = NetworkTools.pickInterface(settings.interfaceAddress);
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        int chosen = bind(settings.port);
        serverSocket.setSoTimeout(1000);
        port = chosen;
        running = true;

        ui.deviceName = settings.deviceName;
        ui.port = chosen;
        ui.interfaceAddress =
            networkInterface.networkInterface().getName() +
            " " +
            networkInterface.address().getHostAddress();
        Log.info("net", "interfaces: " + NetworkTools.describeInterfaces());
        Log.info(
            "raop",
            "listening on " +
                networkInterface.address().getHostAddress() +
                ":" +
                chosen +
                " via " +
                networkInterface.networkInterface().getName() +
                " (" +
                settings.deviceName +
                ")"
        );

        acceptThread = new Thread(this::acceptLoop, "rtsp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        if (advertise) {
            advertise();
        }
        ui.changed();
    }

    private int bind(int requested) throws IOException {
        if (requested > 0) {
            serverSocket.bind(new InetSocketAddress(requested), 50);
            return requested;
        }
        IOException last = null;
        for (
            int candidate = Settings.PORT_RANGE_START;
            candidate <= Settings.PORT_RANGE_END;
            candidate++
        ) {
            try {
                serverSocket.bind(new InetSocketAddress(candidate), 50);
                return candidate;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no free port") : last;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                RtspConnection connection = new RtspConnection(this, recorder, socket);
                Thread thread = new Thread(
                    connection,
                    "rtsp-" + connection.clientAddress().getHostAddress()
                );
                thread.setDaemon(true);
                thread.start();
            } catch (java.net.SocketTimeoutException ignored) {
                // periodic wakeup
            } catch (SocketException e) {
                if (running) {
                    Log.warn("raop", "accept failed: " + e.getMessage());
                }
                break;
            } catch (Exception e) {
                Log.warn("raop", "accept failed: " + e.getMessage());
            }
        }
    }

    private void advertise() {
        MulticastProbe probe = probeMulticast();
        if (!probe.available()) {
            ui.advertising = false;
            ui.advertisedAs = "";
            ui.lastError = "mDNS multicast blocked: " + probe.error();
            Log.error(
                "mdns",
                "Cannot advertise over mDNS: " +
                    probe.error() +
                    ". On macOS allow 'Local Network' for the app that started this process " +
                    "(Terminal, iTerm, IntelliJ IDEA) under System Settings -> Privacy & Security -> " +
                    "Local Network, then restart it. Also check VPNs or network filters. " +
                    "The receiver stays invisible in the AirPlay menu until this is fixed."
            );
            return;
        }
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("txtvers", "1");
        txt.put("ch", "2");
        txt.put("cn", "0,1");
        txt.put("et", "0,1");
        txt.put("sv", "false");
        txt.put("da", "true");
        txt.put("sr", "44100");
        txt.put("ss", "16");
        txt.put("pw", "false");
        txt.put("vn", "3");
        txt.put("tp", "UDP");
        txt.put("md", "0,1,2");
        txt.put("sm", "false");
        txt.put("ek", "1");
        txt.put("vs", "130.14");
        txt.put("am", "AirPort10,115");
        txt.put("sf", "0x4");
        String name = ReceiverId.format(deviceId) + "@" + settings.deviceName;
        try {
            String hostName = networkInterface.address().getHostAddress().replace('.', '-');
            jmds = JmDNS.create(networkInterface.address(), hostName);
            ServiceInfo info = ServiceInfo.create("_raop._tcp.local.", name, port, 0, 0, txt);
            jmds.registerService(info);
            ui.advertising = true;
            ui.advertisedAs = name;
            Log.info(
                "mdns",
                "advertising '" +
                    settings.deviceName +
                    "' as " +
                    name +
                    "._raop._tcp on " +
                    networkInterface.address().getHostAddress() +
                    " via " +
                    networkInterface.networkInterface().getName()
            );
        } catch (Exception e) {
            ui.advertising = false;
            ui.lastError = "mDNS advertisement failed: " + e.getMessage();
            Log.error(
                "mdns",
                "cannot advertise receiver: " +
                    e.getMessage() +
                    ". Check that UDP 5353 (mDNS) is allowed by the DSM firewall and that the " +
                    "NAS network is the same LAN/subnet as the AirPlay client."
            );
        }
    }

    private record MulticastProbe(boolean available, String error) {}

    /**
     * Sends a single packet to the mDNS group before starting JmDNS. When macOS denies local network
     * access, every send fails with {@code No route to host} and JmDNS would otherwise log a stack
     * trace per state task instead of telling the user what to fix.
     */
    private MulticastProbe probeMulticast() {
        try (MulticastSocket socket = new MulticastSocket(0)) {
            socket.setNetworkInterface(networkInterface.networkInterface());
            socket.setTimeToLive(255);
            byte[] payload = new byte[] { 0 };
            socket.send(
                new DatagramPacket(
                    payload,
                    payload.length,
                    InetAddress.getByName("224.0.0.251"),
                    5353
                )
            );
            return new MulticastProbe(true, null);
        } catch (Exception e) {
            return new MulticastProbe(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public synchronized InetAddress interfaceAddress() {
        return networkInterface == null ? null : networkInterface.address();
    }

    /** The AirPlay device ID; senders check it in the Apple-Challenge response. */
    public byte[] deviceId() {
        return deviceId.clone();
    }

    public int port() {
        return port;
    }

    void reportSenderApp(String app) {
        ui.senderApp = app == null ? "" : app;
        ui.changed();
    }

    /** Another device is already streaming to this receiver. */
    public static final class BusyException extends IOException {

        public BusyException(String message) {
            super(message);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public StreamSession startSession(
        InetAddress client,
        InetAddress localAddress,
        Sdp sdp,
        int controlPort,
        int timingPort
    ) throws IOException {
        synchronized (this) {
            if (session != null && !client.equals(sessionClient)) {
                throw new BusyException("receiver busy with " + sessionClient);
            }
            if (session != null) {
                endSession(session);
            }
            StreamSession created = new StreamSession(
                client,
                localAddress,
                sdp,
                controlPort,
                timingPort,
                recorder,
                settings
            );
            created.start();
            session = created;
            sessionClient = client;
            recorder.onClientConnected(client.getHostAddress());
            ui.client = client.getHostAddress();
            ui.changed();
            return created;
        }
    }

    public synchronized void endSession(StreamSession ending) {
        if (ending == null || session != ending) {
            return;
        }
        session = null;
        sessionClient = null;
        ending.stop();
        recorder.onSessionEnded();
        ui.client = "";
        ui.changed();
    }

    public synchronized void stop() {
        running = false;
        endSession(session);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
        if (jmds != null) {
            try {
                jmds.unregisterAllServices();
                jmds.close();
            } catch (IOException ignored) {
                // closing
            }
            jmds = null;
        }
        ui.advertising = false;
        ui.changed();
        Log.info("raop", "receiver stopped");
    }

    public synchronized void restart() throws IOException {
        stop();
        start();
    }
}
