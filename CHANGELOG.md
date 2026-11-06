# Changelog

All notable changes to AirPlay Capture are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/moritzfl/airplay-capture/compare/1.0.2...HEAD
[1.0.2]: https://github.com/moritzfl/airplay-capture/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/moritzfl/airplay-capture/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/moritzfl/airplay-capture/releases/tag/1.0.0
