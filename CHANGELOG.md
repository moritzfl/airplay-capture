# Changelog

All notable changes to AirPlay Capture are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- Updated the bundled Java runtimes to address [Java4Shell (CVE-2026-114938)](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/).
  The x86-64 Docker image and both Synology packages use Eclipse Temurin 21.0.13.1+2. The ARM64
  Docker image is unaffected. These builds no longer need the temporary Base64 workaround.
- The standalone jar does not bundle Java. Update your installed runtime to a patched build,
  such as Eclipse Temurin 21.0.13.1+2 or later.

## [2.0.2] - 2026-11-19

### Added

- On Synology, the Login Portal page (the address for phones) also offers **Open in new tab**, left
  of the account name, to show the app without DSM's header. That tab can be added to the Home
  Screen and then opens like an app.

### Changed

- The Synology package uses Docker Bridge 0.4.1.

### Security

- The web interface is local-only by default. Remote installations should use a password and
  HTTPS; sign-in attempts are rate-limited. Synology now authenticates the bridge connection
  and runs the app as its DSM package user. Grant that user access to the recordings folder.
- The receiver limits unauthenticated requests and accepts audio only from the session peer.
  Long recordings keep their packet tables on disk, and audiobook preview and conversion work
  is bounded.

## [2.0.1] - 2026-11-18

### Added

- On Synology, the button left of Minimize in the window title bar opens the app on its own in a
  browser tab: full page, signed in the same way, and bookmarked addresses survive a reload.

### Fixed

- The Docker images and the Synology package start the JVM with `-XX:-UseBASE64Intrinsics`, which
  closes the Java4Shell path (CVE-2026-114938) until the base image ships the fixed JDK.

## [2.0.0] - 2026-11-17

### Changed

- The ffmpeg executable and library location can only be configured on the host.
- Sign-in and sign-out now match the app, with clear feedback after a failed sign-in.
- The offline receiver card links directly to Logs. On phones, recording actions and Settings
  sections are easier to reach.

### Removed

- Removed the integration HTTP API for status, track and group listings, logs, settings, and track
  deletion. Browser playback, covers, previews, and downloads keep their existing URLs.

### Fixed

- Media endpoints handle suffix ranges and reject unsatisfiable ranges, improving seeking.
- Failed or interrupted exports no longer cause the raw CAF to be deleted during startup.
  Settings and library writes no longer truncate the previous state before replacing it.
- Restarting the receiver closes old control connections, and an unresponsive ffmpeg version
  check no longer blocks indefinitely.
- Export filenames avoid collisions with existing recordings across formats.

## [1.2.0] - 2026-11-14

### Added

- The app checks GitHub once a day for a newer release and shows an **Update** notice in the
  header, with the version and a link to the release under **Settings → Updates**. The check can
  be turned off there.
- A player bar at the bottom of the window that keeps playing while you move between Library,
  Settings and Logs. The play button of the playing recording becomes pause.
- The log view follows the newest line, can show warnings and errors only, and copies its lines
  for a bug report.

### Changed

- Library: titles are no longer cut off, recordings inside an album or book show their track
  number instead of repeating cover, artist and album, and recordings of today and yesterday say
  so. On small screens the library is one column of compact cards.
- Settings are grouped into one card per topic with a list to jump between them. A bar appears
  when something was changed and confirms the save; problems are shown on the field. Options that
  have no effect are disabled, and the network interface is chosen from a list.
- The audiobook and edition dialogs use the full height of the window and the full screen on
  phones, and say right away when a catalog edition does not fit the recording.
- The conversion history folds to a one-line summary once its newest entry is more than a day old.
  The running conversion is listed first, then the waiting ones in the order they will start.
- The header shows the package icon and a short status (Ready, Recording, Receiver offline); on
  phones the navigation is a tab row. The card above the library says when the receiver is
  offline, and why, instead of inviting you to look for it in the AirPlay menu.

### Fixed

- Dates and times follow the time zone of the browser instead of the server, which is usually UTC
  in a container.
- `/api/status` reports the real application version.
- Searching for a song showed every recording of its album instead of the matching ones.
- **Show in library** left the book collapsed in the table and highlighted only its title.
- Delete and cancel confirmations opened with the focus on the destructive button, so Enter
  confirmed them.
- An interface address that does not exist on the machine could be saved and left the receiver
  offline; it is rejected now.
- Browser tabs had no title, and every page load requested a stylesheet that does not exist.

## [1.1.0] - 2026-11-13

### Added

- Automatic audiobook assembly looks up the catalog edition whose length matches the recording.
  When it fits, the book gets that edition's chapter names, title and author, with the chapter
  cuts placed in pauses; otherwise the chapters from the sender are kept.

### Fixed

- Catalog searches missed audiobooks on MusicBrainz when the artist field held a narrator, the
  app or a playlist name, or a differently punctuated author name such as "George R.R. Martin".

### Changed

- Vaadin 25.3.5.

## [1.0.3] - 2026-11-06

### Added

- Synology armv8 package for ARM NAS models with Container Manager. Not yet tested on ARM NAS hardware.

### Fixed

- A finished audiobook conversion could briefly show as ready without its download.
- Stopping the app while the audiobook queue was being saved could leave its file incomplete; the
  app now lets the queue finish writing first.

## [1.0.2] - 2026-11-06

### Fixed

- iPhones and Macs could not connect to the receiver ("Connection could not be established")
  when another installation on the same machine had used the same AirPlay identity, for example
  after replacing an older package. Each installation now creates its own AirPlay device ID once
  and keeps it in `receiver-id` in the data folder. After updating, AirPlay devices see the
  receiver as a new device under the same name.

### Changed

- Synology: **Manage → Open on phones…** can now turn the app's Login Portal address off again,
  not only on.

## [1.0.1] - 2026-11-05

### Fixed

- The release now includes the Synology package (`.spk`) and the runnable jar; 1.0.0 was
  published as Docker images only.

## [1.0.0] - 2026-11-05

### Added

- First release of AirPlay Capture: an AirPlay receiver that records every song as its own file
  with cover art and metadata, managed in a web interface.
- Lossless capture with export to AAC (`.m4a`), MP3 and ALAC, including tags and cover art.
- Audiobook detection and a reviewed conversion of chapters into one chaptered `.m4b`, with a
  preview of every cut.
- Library grouped by album and book, ZIP downloads, playback and duplicate takes.
- Synology package for DSM 7.2.1+ with Container Manager, served through DSM's own ports.
- Docker images for linux/amd64 and linux/arm64 on `ghcr.io`.
- Runs natively on macOS, Linux and Windows.

[Unreleased]: https://github.com/moritzfl/airplay-capture/compare/2.0.2...HEAD
[2.0.2]: https://github.com/moritzfl/airplay-capture/compare/2.0.1...2.0.2
[2.0.1]: https://github.com/moritzfl/airplay-capture/compare/2.0.0...2.0.1
[2.0.0]: https://github.com/moritzfl/airplay-capture/compare/1.2.0...2.0.0
[1.2.0]: https://github.com/moritzfl/airplay-capture/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/moritzfl/airplay-capture/compare/1.0.3...1.1.0
[1.0.3]: https://github.com/moritzfl/airplay-capture/compare/1.0.2...1.0.3
[1.0.2]: https://github.com/moritzfl/airplay-capture/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/moritzfl/airplay-capture/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/moritzfl/airplay-capture/releases/tag/1.0.0
