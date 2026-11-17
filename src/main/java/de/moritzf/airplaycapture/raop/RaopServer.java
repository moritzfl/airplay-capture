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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
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
        ServerSocket listener = new ServerSocket();
        int chosen;
        try {
            listener.setReuseAddress(true);
            chosen = bind(listener, settings.port);
        } catch (IOException | RuntimeException e) {
            closeSocket(listener);
            throw e;
        }
        serverSocket = listener;
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

        acceptThread = new Thread(() -> acceptLoop(listener), "rtsp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        if (advertise) {
            advertise();
        }
        ui.changed();
    }

    private static int bind(ServerSocket listener, int requested) throws IOException {
        if (requested > 0) {
            listener.bind(new InetSocketAddress(requested), 50);
            return requested;
        }
        IOException last = null;
        for (
            int candidate = Settings.PORT_RANGE_START;
            candidate <= Settings.PORT_RANGE_END;
            candidate++
        ) {
            try {
                listener.bind(new InetSocketAddress(candidate), 50);
                return candidate;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no free port") : last;
    }

    private void acceptLoop(ServerSocket listener) {
        while (!listener.isClosed()) {
            Socket socket = null;
            try {
                socket = listener.accept();
                connections.add(socket);
                if (listener.isClosed()) {
                    connectionClosed(socket);
                    break;
                }
                RtspConnection connection = new RtspConnection(this, recorder, socket);
                Thread thread = new Thread(
                    connection,
                    "rtsp-" + connection.clientAddress().getHostAddress()
                );
                thread.setDaemon(true);
                thread.start();
            } catch (SocketException e) {
                connectionClosed(socket);
                if (!listener.isClosed()) {
                    Log.warn("raop", "accept failed: " + e.getMessage());
                }
                if (socket == null) {
                    break;
                }
            } catch (Exception e) {
                connectionClosed(socket);
                Log.warn("raop", "accept failed: " + e.getMessage());
            }
        }
    }

    void connectionClosed(Socket socket) {
        if (socket != null) {
            closeSocket(socket);
            connections.remove(socket);
        }
    }

    private static void closeSocket(java.io.Closeable socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing.
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

    synchronized void withSession(StreamSession owner, Runnable action) {
        if (owner != null && owner == session) {
            action.run();
        }
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

    StreamSession startSession(
        Socket socket,
        InetAddress client,
        InetAddress localAddress,
        Sdp sdp,
        int controlPort,
        int timingPort,
        String senderApp
    ) throws IOException {
        synchronized (this) {
            if (!running || socket.isClosed() || !connections.contains(socket)) {
                throw new SocketException("receiver stopped");
            }
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
            session = created;
            sessionClient = client;
            recorder.onStreamConfigured(sdp.alac());
            recorder.onClientConnected(client.getHostAddress());
            ui.senderApp = senderApp;
            created.start();
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
        if (serverSocket != null) {
            closeSocket(serverSocket);
            serverSocket = null;
        }
        connections.forEach(this::connectionClosed);
        endSession(session);
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
