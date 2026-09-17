# Contributing

Thanks for helping! Issues and pull requests are welcome in English or Russian.

## Ground rules

- **Never commit or attach personal photos, videos or texts.** Use synthetic samples or files whose licence allows redistribution, and describe where they come from in `core/src/test/resources/fixtures/README.md`.
- Keep the app offline: no `INTERNET` permission, no analytics, no network libraries.
- The project removes *file metadata* and *hidden characters*. Pull requests that try to defeat pixel- or text-level watermarks (e.g. SynthID) or AI-text detectors are out of scope and will be declined.

## Development setup

- **JDK 17 or 21.** Gradle 8.13 does not run on JDK 25:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ```
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0`. Point `local.properties` or `ANDROID_HOME` at it.
- **A device or emulator (Android 10+)** for instrumented tests.

```bash
./gradlew :core:test                      # fast JVM tests: parsers, text, fixtures, fuzzing
./gradlew :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest  # end-to-end on a device
```

**Optional independent verification.** `core/src/test/resources/fixtures/_tools/setup_tools.sh` installs ExifTool, c2pa-python, c2patool and FFmpeg into `~/.cache/clear-content-tools`. Then run `verify_media.py` on the cleaned files; `FixtureTest` writes them to `core/build/fixture-out/`.

## Workflow

1. Fork the repository and create a branch from `main` (`feat/…`, `fix/…`, `docs/…`).
2. Keep commits focused and use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `test:`, `docs:`, `ci:`, `chore:`).
3. Add or update tests:
   - every new container, marker or text rule needs a unit test;
   - new file formats need a fixture.
4. Add a line under **Unreleased** in `CHANGELOG.md` for user-visible changes.
5. Open a pull request. CI (build, unit tests, lint, emulator tests) must pass.

`main` is protected: changes land only through pull requests with green checks, and history is linear (squash merge).

## Code style

- Kotlin official style (`kotlin.code.style=official`) and `.editorconfig`.
- Match the surrounding code; add comments only where the *why* isn't obvious.
- User-facing strings are in Russian (`Labels.kt` / `strings.xml`); code, comments and commit messages are in English.
- Don't write invisible characters literally in source files. Use code points (`0x200B`) or `\uXXXX` escapes instead.

## Releases

Maintainers release by tagging:

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Move the **Unreleased** notes in `CHANGELOG.md` to the new version.
3. Merge to `main`, then tag and push:
   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z"   # or -s to sign the tag with GPG/SSH
   git push origin vX.Y.Z
   ```

The **Release** workflow then:
- builds and tests the app;
- signs the APK with the release key;
- attaches the APK, `SHA256SUMS.txt` and the signing certificate digest to a GitHub release;
- records a [build provenance attestation](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations).

The workflow needs these repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

Without them the workflow skips publishing with a warning. Locally, `scripts/build-release.sh` uses `keystore.properties`, which is ignored by git. The release key must stay the same across versions; otherwise users cannot update.
