# Security policy

## Supported versions

Only the latest release receives fixes.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead:
**Security → Report a vulnerability**
(<https://github.com/izhddm/clear-content-android/security/advisories/new>).

Include the app version, the Android version and the steps to reproduce. If a crafted file triggers the problem, describe how to build it instead of attaching real personal media.

You can expect an acknowledgement within 7 days. Fixes are released as soon as practical, and reporters are credited unless they prefer otherwise.

## Scope

The following are in scope:
- crashes, hangs or memory exhaustion caused by crafted media or text;
- metadata that survives cleaning while the app reports the file as clean;
- data leaving the device;
- unintended file access or deletion (including the *replace originals* feature);
- problems in the release signing or CI pipeline.

Watermarks embedded in pixels or wording (e.g. SynthID) are intentionally out of scope. See the README.
