# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Toolchain: Android Gradle Plugin 9.4 (built-in Kotlin), Gradle 9.7.1, Kotlin 2.4.20, compileSdk 37.
- Libraries: Compose BOM 2026.09.00, AndroidX Core 1.19, Activity 1.13, Lifecycle 2.11, DataStore 1.2.1, Coil 3.6.2, kotlinx.coroutines 1.11.
- Dependabot groups all Gradle updates into one pull request, because toolchain and AndroidX updates depend on each other.

## [1.1.0] - 2026-09-17

### Added
- **Replace originals** option (Android 11+): the clean copy takes the original's album, file name and capture date, and the original is deleted after one system confirmation per batch. No duplicates in the gallery.
- A capture-date-only EXIF (`DateTimeOriginal` + `OffsetTimeOriginal`) is written in replace mode, so photos keep their place in the timeline.
- A "Delete originals from gallery" button for batches whose confirmation was postponed.
- A link to the source code in *Settings → About*.

## 1.0.1 - 2026-09-17

Pre-publication build, not tagged.

### Added
- A foreground service (`mediaProcessing` / `dataSync`) keeps processing alive after "Share → Back".
- Shared and clipboard files are copied into the app cache right away, so processing no longer depends on the sender's temporary permission.
- Fuzz tests for all container parsers and the text sanitizer.

### Fixed
- Deeply nested MP4 boxes could overflow the stack and stop the queue.
- A JFIF header that declares a missing thumbnail prevented lossless cleaning.
- Text cleaning is now idempotent (a ZWJ before a removed character was kept).
- DASH init segments without frames are rejected instead of being published empty.
- Clearer error messages for revoked or missing files.

## 1.0.0 - 2026-09-17

Pre-publication build, not tagged.

### Added
- Lossless metadata removal for JPEG, PNG, WebP, GIF, MP4 and MOV; re-encoding for HEIC, AVIF, TIFF and BMP; remuxing for fragmented MP4 and WebM.
- Detection of C2PA manifests (embedded and remote), IPTC Digital Source Type, XMP, EXIF/GPS, generation parameters and 25+ AI generators.
- Hidden-character and chatbot-artifact cleaning for text, with optional Markdown and typography normalisation.
- Share-sheet target, Quick Settings tile, text-selection action, launcher shortcuts and one-tap Instagram sharing.
- Desktop CLI (`clear-content scan | clean | text`).

[Unreleased]: https://github.com/izhddm/clear-content-android/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/izhddm/clear-content-android/releases/tag/v1.1.0
