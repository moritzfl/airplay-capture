# AirPlay Capture

AirPlay receiver that records every song as a separate file with cover art and metadata, managed
through a web interface.

Start playback on an iPhone, iPad or Mac, pick the receiver in the AirPlay menu, and each track
lands in your library as `Artist - Title.m4a` (and optionally `.mp3`), ready for MP3 players,
Toniebox and friends.

* runs on macOS and Linux, or in Docker
* keeps the original lossless ALAC frames and exports AAC/MP3/ALAC with ffmpeg
* per-track splitting from AirPlay metadata, with silence/timeout fallback
* embedded cover art, title, artist, album, genre, track number
* flags recordings that stop before the duration reported by the sender (`incomplete`)
* audiobook chapters are detected; a reviewed conversion turns a group into one chaptered `.m4b`
* fills missing album/genre/year/track/cover from Apple's catalog (sender values always win)
* web UI (Vaadin): live "now recording" panel, searchable library, playback, per-format download,
  delete, settings and logs
* albums and audiobooks appear as one expandable row; groups download as ZIP, duplicates stay
  visible as separate takes
* sender shown as LAN device name plus app name when the AirPlay sender exposes it
* mDNS advertisement as a normal AirPlay 1 receiver (`_raop._tcp`)

## Stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 21, Spring Boot 4.1 |
| UI | Vaadin 25 (server-side, dark Lumo theme), server push |
| Receiver | plain Java: RTSP/RTP, RSA-OAEP + AES-128-CBC, mDNS via JmDNS |
| Capture | lossless ALAC frames muxed into CAF, no native code |
| Export | ffmpeg (AAC, MP3, ALAC) with tags and cover art |

The receiver/recording core has no Spring or Vaadin dependency; the web layer is a thin shell around
it.

## Quick start (macOS / Linux)

Requirements: JDK 21, ffmpeg (`brew install ffmpeg` / `apt install ffmpeg`).

```bash
./gradlew bootRun
```

Open <http://localhost:8080/app/>, pick "AirPlay Capture" in the AirPlay menu of your device and
play something.

On macOS 15 and newer, allow the terminal (or IDE) that starts the app under
**System Settings → Privacy & Security → Local Network**. Without that permission mDNS multicast is
blocked, the receiver never appears in the AirPlay menu, and the log shows a successful
advertisement even though no packets leave the machine.

Build a runnable jar:

```bash
./gradlew bootJar
java -jar build/libs/airplay-capture-1.0.0.jar --data ~/airplay-data
```

### IntelliJ IDEA

Shared run configurations are in `.run/`:

| Configuration | Kind |
| --- | --- |
| `AirPlay Capture (Spring Boot)` | Spring Boot main class, data in `./data` |
| `AirPlay Capture (Java)` | plain Application configuration |
| `AirPlay Capture (bootRun)` | Gradle `bootRun` |

All three need the Gradle build (the Vaadin client bundle is copied into the runtime classpath by
`classes`). If the project was imported with a different module name, select the main module in the
run configuration once.

Run options and environment variables:

| Option | Environment | Default |
| --- | --- | --- |
| `--data <dir>` | `AIRPLAY_CAPTURE_DATA` | macOS: `~/Library/Application Support/AirPlayCapture`, Linux: `~/.airplay-capture` |
| `--web-port <port>` | `AIRPLAY_CAPTURE_WEB_PORT` | `8080` |
| - | `AIRPLAY_CAPTURE_WEB_USER` | `recorder` (only used with a password) |
| - | `AIRPLAY_CAPTURE_WEB_PASSWORD` | unset - web UI/API have no authentication |

Settings (receiver name, port, formats, bitrates, silence timeout, ffmpeg path, lost-packet
recovery) are stored in `<data>/settings.json` and can be edited in the web UI.

## Docker

The default compose file publishes the web UI on port 8080. This works on macOS and Linux, but a
bridged container cannot advertise itself on the LAN, so AirPlay playback will not reach it:

```bash
docker compose up -d --build
```

Then open <http://localhost:8080/app/>.

To actually receive AirPlay in Docker, the container needs host networking (mDNS and the RTP/UDP
audio path). Use the host-networking variant on Linux:

```bash
docker compose -f docker-compose.host.yml up -d --build
```

On Docker Desktop for macOS, host networking is an opt-in beta
(**Settings → Resources → Network → Enable host networking**) and may still not expose mDNS
reliably. The dependable way to record on macOS is to run the app natively (`./gradlew bootRun`);
the Docker setup is useful there for browsing the library.

Files are written to `./data`:

```
data/
├── library/                 # audio files (the only folder you need to copy elsewhere)
├── covers/                  # cover images (also embedded in the files)
├── tmp/                     # in-progress recordings
├── settings.json
└── library.json
```

On Docker Desktop for macOS, host networking is not available; run the app natively there instead.

## Metadata completion

AirPlay metadata is supplied by the sender and is not guaranteed: Apple Music usually sends title,
artist and album, but genre, year, track number and artwork are often missing; other apps send much
less. When **Fill missing tags from Apple catalog** is enabled, the exporter looks the track up in
Apple's keyless iTunes catalog and fills only empty fields:

* album, genre, year, track number and cover art
* only if empty - sender-provided values are never overwritten
* only with a strict title/artist match (album match as well when the sender supplied one)
* Apple first, Deezer as fallback when Apple has no match
* one cached album lookup per album, artwork downloaded only when the sender sent none
* entries touched this way are marked `via apple` (or `via deezer`) in the UI

This runs on the export thread, so recording is not delayed. The catalog country is shared with the
audiobook lookup. Deezer is keyless; it is used for tags and, for audiobooks, for chapter lists.
MusicBrainz is not used for music tags (its release/recording matching is sparser than commercial
catalogs); it confirms audiobooks and provides a chapter-list fallback. Disable the options if
metadata should not leave the machine.

## Synology SPK

`packaging/` builds a sideloadable Synology package through the generic
[synology-docker-bridge](https://github.com/moritzfl/synology-docker-bridge) framework, checked
out next to this repository. The SPK contains the image, so no registry is needed:

```sh
git clone https://github.com/moritzfl/synology-docker-bridge.git ../synology-docker-bridge
./packaging/pack-framework.sh
# or: FRAMEWORK_DIR=/path/to/synology-docker-bridge ./packaging/pack-framework.sh
```

Install the resulting `packaging/dist/airplay-capture-*.spk` with Package Center → Manual Install
(DSM 7.2.1+ with Container Manager). Open the **AirPlay Capture** desktop icon; the window embeds
the web UI and DSM authenticates the session. The UI is served through DSM's own ports, so no extra
port is exposed and no firewall rule is needed.

Recordings go to the default DSM music share, in `/volume1/music/airplay-capture`. The app sees the
stable path `/output-parent/.airplay-capture-folder`; changing the folder in **Settings** moves the
existing recordings and repoints that symlink without recreating the container. Settings and covers
stay in the `airplay-capture` shared folder. The package uses Container Manager's compose project
with host networking for mDNS and the audio stream. See [packaging/README.md](packaging/README.md)
for details.

Verified on Virtual DSM 7.2.2 with Container Manager 24.0.2: install, upgrade, reboot, desktop
window over HTTP and HTTPS, settings, restart, and output folder change.

If the receiver does not appear in the AirPlay menu, check the log line
`listening on <ip> via <interface>`; it must show the NAS LAN address. Set
**Settings → Interface address** to that address if the picked interface was wrong. With the DSM
firewall enabled, allow IGMP and UDP 5353 for mDNS discovery (and the RTSP/audio ports for
playback). See [packaging/README.md](packaging/README.md#receiver-is-not-visible-in-the-airplay-menu).

## How recording works

1. The receiver advertises `_raop._tcp` and accepts the RTSP handshake (ANNOUNCE/SETUP/RECORD).
2. Audio arrives as AES-128-CBC encrypted RTP packets, one ALAC frame per packet. Packets are
   reordered and missing ones are requested again from the sender.
3. DMAP metadata (`minm`, `asar`, `asal`, `asgn`, ...), artwork and progress arrive over RTSP and are
   attached to the running track. A metadata change, FLUSH, disconnect or configurable silence
   (default 6 s) closes the track.
4. The ALAC frames are stored in a CAF container (lossless, no native code) and ffmpeg exports the
   configured formats with tags and cover art.

Recordings shorter than the configured minimum (default 3 s) are discarded.

## Formats

| Setting | Output | Notes |
| --- | --- | --- |
| `m4a` | AAC in MP4 | default 192 kbps, plays everywhere Apple does |
| `mp3` | MP3 (LAME VBR or CBR) | VBR quality V0-V9 or a fixed bitrate; best for old MP3 players and Toniebox tooling |
| `alac` | ALAC in MP4 | lossless copy of the received stream |
| `m4b` | AAC audiobook | generated per book, contains chapters and cover |

If ffmpeg is missing, the lossless `.caf` capture is kept instead and the UI marks the track.

### Downloading

The library groups recordings by album (or by book title for album-less audiobook chapters) and
cover art. Same-named albums or books with different covers stay separate rows, so two parts of a
series are never assembled into one file. A group is one expandable row:

* `ZIP · M4A` / `ZIP · MP3` downloads every recording of the group as one archive (best take per
  title),
* `M4B` downloads an assembled audiobook directly,
* expanding the group shows each recording, and each format chip (`M4A`, `MP3`, `ALAC`, `M4B`)
  downloads exactly that file with the size in its tooltip,
* the arrow next to a row downloads the preferred file (`.m4b` for a book, otherwise the first
  configured format).

Normal music albums use the same group row: ZIP, per-track playback and downloads are unchanged.
The compact `M4B` button is only an optional action, it does not turn an album into a book.
Recordings are ordered by sender track number when available, otherwise by recording time.

ZIPs are streamed on demand and contain the best take per duplicate title; add `?all=1` to the ZIP
URL to include every take. Downloads are served as `Content-Disposition: attachment` with the real
filename, so Safari and other browsers keep `.m4b`/`.mp3` instead of deriving an extension from the
MP4 MIME type.

### Duplicate takes

Re-recording the same track (same title and artist inside one group) never merges the files. All
takes stay visible as separate rows:

* the best take is marked `best take`; the others are `take 2`, `take 3`, ...
* ranking is complete before incomplete, then fewer lost packets, then longer, then newer,
* ZIP downloads and audiobook assembly use the best take by default; other takes remain playable
  and downloadable.

## Dropouts and retransmits

AirPlay 1 (RAOP) is a real-time stream: the sender chooses the transmit buffer (typically around
two seconds) and decides how long it keeps packets for retransmission. The receiver cannot request
a larger buffer over this protocol. The recorder therefore makes the recovery path as generous as
the protocol allows:

* packets are reordered in a 4096-packet buffer instead of being written as they arrive,
* missing sequence numbers are requested on the control channel every 150 ms, covering every
  outstanding 64-packet range rather than only the first one,
* a gap is only accepted after **Lost-packet recovery** (default 2.5 s) without a retransmit.

A longer window recovers more packets but delays the moment the damaged region is written. Once a
gap is accepted, late retransmits for it are ignored. Unrecovered runs are shown as `audio gaps` in
the library with the exact packet count and lost milliseconds. AirPlay 2 buffered-audio streams
(HomePod-style) are not implemented; this receiver speaks RAOP only.

## Audiobooks

Audiobook detection marks chapters and offers a conversion action in the library, but it does not
assemble anything by default. Expand an album/book group and choose **Create audiobook…**:

1. **Recordings** - best takes are preselected; duplicate takes stay visible but are unchecked.
   Uncheck anything that should not go into the book.
2. **Metadata** - title and author are prefilled. **Find editions** opens a picker with Apple Books,
   Deezer and MusicBrainz matches, sorted by length fit. Chapter editions come first; Apple Books
   entries are metadata-only. Selecting an edition fills title/author and, when available, its
   chapter list. Both fields stay editable.
3. **Chapters** - **From catalog** uses the selected chapter names and durations, with MusicBrainz as
   fallback, and aligns the cuts to the recording. **At pauses** splits at silence of at least 2.5 s
   with at least 5 minutes per chapter. **One chapter per recording** keeps every selected recording
   whole. Catalog mode falls back to pauses when no matching edition is found.
4. **Preview** - **Preview chapter cuts** analyses the exact plan and lists every chapter with its
   start and duration. `▶` plays a boundary sample (first 10 s and last 8 s, or the whole chapter
   when shorter than 20 s); **Full** plays the whole chapter. Chapters that are already separate
   recordings stream instantly; chapters produced by a split are encoded on first request. Changing
   sources or the chapter mode invalidates the preview; it is re-analysed against the new selection.
5. **Output** - AAC bitrate for the `.m4b`. Creating after a preview uses that exact analysed plan,
   so the file matches what was checked.

The job runs in the background. It uses the best existing source per recording (ALAC > M4A > MP3 >
CAF), keeps all source recordings, and adds the `.m4b` to the group. M4A sources are re-encoded once;
configure ALAC if perfect quality matters.

### Conversion queue

Every conversion becomes a job in the **Audiobook conversions** card above the library, so nothing
disappears behind a toast:

* **Queued** jobs wait for the current conversion and can be cancelled before they start.
* **Converting** shows the current step (`Joining recordings`, `Detecting pauses`, `Encoding
  audiobook at 96 kbps`) with a percentage taken from ffmpeg's own progress output.
* **Done** keeps the finished book with a **Download M4B** button, **Show in library** (scrolls to
  and flashes the row) and a dismiss button. Finished entries stay until they are dismissed or
  **Clear finished** is used - that only clears the list, never the files.
* **Failed** shows the reason inline and keeps it until dismissed.

The header shows a compact `Converting · 42%` pill on every page while a job runs, and the library
row shows `queued` or the live percentage instead of hiding its action. The queue is stored in
`audiobook-jobs.json` in the data directory, so it survives a restart; a job that was interrupted by
a restart is shown as failed rather than silently vanishing. Only the three newest finished jobs are
listed, older ones stay in the file until **Clear finished**.

`scripts/ux-fixture.mjs <dir>` builds a throwaway library with a 40-minute recording for exercising
the queue by hand. Chapter boundaries come from the catalog when an edition
matches, otherwise from sender marks and pauses - the user reviews sources, metadata and the preview,
not every individual cut.

To restore the old behavior, enable **Assemble detected audiobooks automatically**. The automatic
path keeps the lossless CAF until the book ends; the manual path starts from the exported files.
Automatic assembly still uses the pause-based plan; use **Create audiobook** to apply catalog
chapters.

AirPlay does not transmit chapter marks. Catalog chapter lists are matched by title, artist and the
expected total duration, then scaled to the recording and moved into nearby pauses (within 8 s), so
a chapter does not start mid-word. The duration match is what separates the right edition from an
unabridged sibling; when the catalog is more than 15% longer or shorter, the plan falls back to
pauses instead of inventing cuts. If the recording is longer than the catalog, the planner searches
for a start offset that aligns the chapter boundaries with pauses; a clear match trims an Audible
announcement before the book (and a trailing announcement) from the `.m4b`. Without pause evidence
the recording is left untrimmed. Apple's iTunes API exposes an audiobook as one item and has no
chapter data; Deezer albums often contain one track per chapter, and MusicBrainz releases expose
their track list. Chapter names come from the catalog. Sender-marked chapters are still moved into
the nearest pause (within 3 s). A long book that arrives as one continuous track can be split at
pauses of at least 2.5 s, with at least 5 minutes per chapter; silence-only splits are numbered.

Detection is configurable in the settings:

| Setting | Default | Meaning |
| --- | --- | --- |
| Detect audiobooks | on | Marks chapters and shows **Create audiobook…** in the library |
| Assemble automatically | off | Build the `.m4b` as soon as a book ends; off = user confirms in the dialog |
| Audiobook genre keywords | `audiobook,audiobooks,hörbuch,hoerbuch` | Genre substring match (case-insensitive) |
| Long-track detection | 900 s | Treat a long track as the start of a book (0 disables) |
| Assemble book after silence | 300 s | Automatic mode only: how long to wait for the next chapter |
| Audiobook bitrate | 96 kbps | Default AAC bitrate of the `.m4b` (editable per job) |
| Keep chapter files | on | Automatic mode only: keep the per-chapter files after assembly |
| Split single-file audiobooks | on | Default for pause mode: split at pauses >=2.5 s, chapters >=5 min |
| Catalog chapter lists | on | Create audiobook dialog: use Deezer/MusicBrainz chapter names and durations when an edition matches |
| Fill missing tags from Apple catalog | on | Complete empty album/genre/year/track/cover |
| Also use Deezer when Apple misses | on | Keyless fallback catalog for tag completion |
| Confirm via Apple Books catalog | on | Query Apple's public audiobook search |
| Also check MusicBrainz | on | Release-group types Audiobook/Spokenword |
| Lookup country | `US` | Two-letter catalog country (e.g. `DE`) |
| MusicBrainz contact | empty | Optional contact for the MusicBrainz User-Agent |

A book starts when a finalized track matches a genre keyword, reports DMAP media kind 8, is
confirmed by the catalog lookup, comes from a known audiobook app, or (if enabled) exceeds the
long-track threshold. Further tracks with the same album continue the book even when the sender
changes the artist per chapter, so apps that play chapters as individual AirPlay tracks still group
correctly. Track numbers order the chapters; otherwise recording order is used. In automatic mode
the book is assembled after the configured silence, when a non-book track starts, or when the
AirPlay session ends; in the default manual mode the library offers **Create audiobook…** instead.

### Catalog lookup

When enabled, album and artist are checked against public catalogs; a title/author match confirms the
book even when the sender provides neither genre nor media kind.

| Provider | Keyless | Audiobook signal | Default |
| --- | --- | --- | --- |
| Apple iTunes Search | yes | `media=audiobook` catalog | on |
| MusicBrainz | yes | release-group type `Audiobook`, `Spokenword`, `Audio drama` | on |
| Deezer | yes | none - search returns music albums only | not used |
| Spotify / Tidal | no (OAuth app) | no public audiobook search | not used |
| Google Books / Open Library | partly | book existence only, no audio format | not used |

* Runs asynchronously - recording never waits for the network.
* Positive results are cached for 7 days, misses for 12 hours; one chain per album+artist.
* The Apple catalog is country-specific (`US`, `DE`, ...); MusicBrainz is global.
* MusicBrainz asks for a contact in the User-Agent; setting one is recommended.
* Disable the lookup entirely if album/artist should not leave the machine.

If the sender supplies no album, the lookup falls back to the title. A title-only match is only
trusted for a long recording (>= the long-track threshold), because film soundtracks and music
albums can share a book title. The sender's AirPlay `User-Agent` is also checked for known
audiobook apps (Audible, Lylli, BookBeat, Storytel, Nextory, Kobo, Blinkist, Scribd, Everand,
Apple/Google Books, Audiobookshelf). The detected app is shown next to the client IP and logged
with the first RTSP request.

This needs album and artist metadata from the sender. If a player does not supply them, the
chapters stay as individual files.

## HTTP API

All endpoints live outside Vaadin's `/app/` mapping and are useful for integrations:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/status` | receiver state, active track, library size |
| `GET` | `/api/tracks` | library listing (includes `audiobookChapter`, `bookId`, `chapterCount`) |
| `GET` | `/api/groups` | album/book groups with stable keys, formats and duplicate count |
| `GET` | `/api/groups/{key}/zip/{format}` | stream a group ZIP (best takes; `?all=1` includes every take) |
| `GET` | `/api/audiobook-preview/{id}/{index}` | boundary preview clip for one planned chapter (internal, one-hour cache) |
| `GET` | `/api/audiobook-preview/{id}/{index}/full` | full chapter audio (source file or generated split) |
| `DELETE` | `/api/tracks/{id}` | delete recording and files |
| `GET` | `/api/tracks/{id}/file/{format}` | stream audio; add `?download=1` for an attachment download |
| `GET` | `/api/tracks/{id}/cover` | cover image |
| `GET` | `/api/now/cover` | cover of the track currently recording |
| `GET`/`PUT` | `/api/settings` | read/update settings (JSON) |
| `GET` | `/api/log` | recent log lines |

## Development

```bash
./gradlew test                # protocol parsing, CAF writer, end-to-end AirPlay session
./gradlew vaadinBuildFrontend # rebuild the client bundle after UI changes
./gradlew bootJar             # runnable jar (builds the frontend automatically)
```

The end-to-end test acts as an AirPlay sender: RTSP handshake, RSA-OAEP key exchange, encrypted RTP
audio, DMAP metadata and artwork, and then verifies the exported file (tags, duration, embedded
cover) with ffprobe.

## Notes and limitations

* Classic AirPlay 1 (RAOP) only. Modern senders fall back to it automatically for audio; AirPlay 2
  features (multi-room, HomePod grouping) are not implemented.
* One sender at a time; a second device gets "453 Not Enough Bandwidth" while a stream is active.
* With `AIRPLAY_CAPTURE_WEB_PASSWORD` set, the UI and API use Spring Security form login. CSRF protection is
  disabled for the same-origin UI and the local API; keep the port on a trusted network and do not
  expose it to the internet.
* Record only content you are allowed to record.

## License

Apache License 2.0, see [LICENSE](LICENSE).
