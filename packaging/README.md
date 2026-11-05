# Synology SPK

The SPK is only an installation vehicle: it embeds the platform-independent Docker image and asks
Container Manager to run it as a compose project. The application itself stays a normal OCI image;
nothing in `src/` knows about Synology. The DSM window, authentication, restart and output-folder
controls come from the generic
[`synology-docker-bridge`](https://github.com/moritzfl/synology-docker-bridge) framework.

The web UI is served through DSM's own nginx, under
`/webman/3rdparty/airplay-capture/proxy/app/`. There is no extra port, no firewall rule and no
certificate exception: the desktop icon behaves like a first-party DSM application. The Docker image
itself stays usable outside Synology with `docker run`.

## Build

Requirements: JDK 21, Docker with buildx, Node.js 22, and a checkout of
`synology-docker-bridge` next to this repository. Run from the repository root:

```sh
git clone https://github.com/moritzfl/synology-docker-bridge.git ../synology-docker-bridge
./packaging/pack-framework.sh
# or: FRAMEWORK_DIR=/path/to/synology-docker-bridge ./packaging/pack-framework.sh
```

The script builds the jar, builds the app and bridge images for `linux/amd64`, embeds both images
and writes:

```
packaging/dist/airplay-capture-1.6.2-x86_64.spk
```

The images are part of the SPK, so installation needs no registry access.

Everything the package needs is declared in `packaging/app.json`: version, images, ports, folders,
icons and the app build command. DSM scripts, `INFO` and the bridge configuration come from the
framework templates, so do not add copies here - edit `app.json` or the framework instead.

## Install

Requirements: DSM 7.2.1+, **Container Manager** installed and running.

1. Package Center → **Manual Install** → choose the SPK.
2. Start the package and open the **AirPlay Capture** desktop icon.

The window embeds the web UI and DSM authenticates the session. The app's internal web port binds
`127.0.0.1` (`SERVER_ADDRESS`), so it cannot be reached directly on the LAN. AirPlay discovery, RTSP
and the audio stream are unaffected.

The package creates the shared folder **airplay-capture** and mounts it as `/data` for settings and
covers. Recordings go to the default DSM music share, `/volume1/music/airplay-capture`. The app sees
the stable path `/output-parent/.airplay-capture-folder`; changing the folder in **Settings** moves the
existing recordings and repoints that symlink without recreating the container. Both shares are kept
across upgrades and uninstalls.

The compose project uses **host networking**, which is required for mDNS discovery, RTSP and the
RTP/UDP audio stream. The app container runs as root inside its namespace so it can write to the DSM
shares; it only touches `/data` and the output parent.

## Window controls

- **Settings**: output folder, whether existing files move, and an optional direct port (`0` keeps
  every extra port closed).
- **Restart**: restarts the app container without recreating it.
- **Open in browser**: opens the same authenticated wrapper in a browser tab, still on DSM's port.

The web UI is only reachable through the bridge. The bridge requires a valid DSM session and the
package is not enabled for all users by default; grant access in **Control Panel → Application
Privileges**.

## Implementation

`conf/resource` declares the `airplay-capture` data share, a `docker-project` that preloads
`image.tar` and manages `docker/compose.yml`, and a `web-config` that registers
`config/dsm.bridge.conf` in DSM nginx. `INFO` declares `install_dep_packages="ContainerManager"` so
DSM starts the package after the Docker daemon is ready.

The bridge container runs as root (Docker socket and the output symlink in the share) and keeps
Container Manager's per-container config IDs in sync; DSM recreates containers on upgrade but does
not refresh those IDs itself. `start-stop-status` waits for both containers but never fails the
package start, so a slow boot does not mark the package broken.

There is no install wizard. The output folder is changed later in the window.

## Receiver is not visible in the AirPlay menu

The web UI can be reachable while AirPlay discovery still fails. Check in this order:

1. **Log** (web UI or `docker logs airplay-capture`): the `net` line lists every interface and the
   `raop`/`mdns` lines name the one in use. It must be the NAS LAN address (for example
   `192.168.1.20`), not `172.17.x.x` (Docker), `10.0.x.x` on a VPN or `127.0.0.1`.
2. If the wrong interface was picked, set **Settings → Interface address** to the NAS LAN IPv4
   address. Saving restarts the receiver; the log then shows `via eth0`/`via ovs_eth0`.
3. **DSM firewall**: mDNS discovery needs IGMP and UDP 5353. If the firewall is enabled, add an
   allow rule for IGMP and UDP 5353 from the LAN, or disable the firewall briefly as a test. For
   playback, the RTSP port (5002 by default) and the UDP audio ports must be reachable as well.
4. The Mac/iPhone must be on the same subnet. mDNS does not cross VLANs unless a repeater is
   configured.
5. Confirm the container uses host networking:
   `docker inspect airplay-capture --format '{{.HostConfig.NetworkMode}}'` → `host`.

## Upgrade / uninstall

- Build a new SPK and install it over the old package. Container Manager recreates the project; the
  data share and output folder are kept. The bridge re-syncs the container IDs so the next boot
  works.
- Uninstall removes the containers and images; the `airplay-capture` share is intentionally
  retained.

## Testing without a NAS

The Virtual DSM tests live in the framework repo, `synology-docker-bridge/test/e2e`. They install
this SPK next to the framework's demo and check the window, the folder move, phone access,
Restart, rights and the Login Portal page, with only DSM's ports exposed:

```sh
spk="$PWD/packaging/dist/airplay-capture-<version>-x86_64.spk"
cd ../synology-docker-bridge/test/e2e
node round.mjs ../../packaging/dist/dsm-nginx-demo-<version>-x86_64.spk "$spk"
```

Virtual DSM uses software emulation on Apple Silicon, so the first start takes a few minutes.
AirPlay/mDNS needs a real LAN interface and cannot be verified through the guest's user-mode
networking; a RAOP sender inside the guest (host network) can stream to the receiver for
checks such as the live cover.
