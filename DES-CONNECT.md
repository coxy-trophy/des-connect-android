# DES Connect for Android

This is a customized fork of KDE Connect Android for the DES UI project. It is
not an official KDE release. Upstream base: 3242aeff092bba165a64d63a690d31627064644d.
Upstream: https://github.com/KDE/kdeconnect-android
Fork source: https://github.com/coxy-trophy/des-connect-android

The initial fork changes the launcher/service label to DES Connect and uses
`io.github.coxytrophy.desconnect` (`.debug` for debug builds), so it can coexist
with official KDE Connect. Internal source/resource namespaces remain upstream
names to minimize churn. Self-notification filtering follows the actual build
application ID rather than the upstream ID. Received-file opening derives its FileProvider authority from the runtime package
to match the manifest. All upstream copyright notices,
COPYING, LICENSES and dependency-license generation are retained. Top bars, device-list title, widgets and notification headers use DES branding;
About links point to this fork while upstream authors/license credits remain. Existing artwork
and many descriptive in-app strings remain upstream in this first step; a full visual redesign
and screen-sharing extensions have not been implemented.

Most source files declare GPL-2.0-only OR GPL-3.0-only OR
LicenseRef-KDE-Accepted-GPL; some third-party files declare Apache-2.0. Inspect
individual files and generated dependency reports before distribution. This fork
is not MIT licensed. Distributed modified builds must follow applicable license
and corresponding-source obligations. Do not use upstream's debug key for a
production release; configure a private release signing key separately.

## Build

Requires a full JDK (not a JRE), Android SDK platform 37 and network access for
Gradle dependencies. The wrapper pins Gradle 9.6.0. Use a local SDK/JDK through
command-scoped variables; never commit local.properties or signing credentials.

```sh
JAVA_HOME=/path/to/full/jdk ANDROID_HOME=/path/to/android/sdk \
  ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2048m \
  testDebugUnitTest assembleDebug
```

No device is required for compilation/unit tests. A real Android device is
required to validate pairing, permissions, background reconnect, notifications,
SMS/calls, transfers and capture/control. None of those are verified by branding.

## Validation on 3 November 2026

Using the existing full JDK21 and Android SDK, `testDebugUnitTest assembleDebug`
passed: 140 tests in 24 suites, zero failures/errors/skips. APK signature check
passed. Package/label inspection confirmed the separate debug ID and DES Connect
label. ADB streamed install failed with splice EINVAL on the Samsung SM-P585;
`adb install --no-streaming -r` succeeded on its Android 14 system.

The final branded app launched successfully. Its device list visibly showed DES
Connect and Devices, and discovered another Android peer. The About screen showed
DES Connect, version information, upstream author credits, licenses and source
controls. No device was paired and no desktop-integration or mirroring claim is
made. Normal OS permission prompts remain; SMS/call/contact/accessibility access
was not granted by automation. The new Rust desktop transport is not implemented.

A first, foreground debug-app sample before the final branding pass measured
161941 KiB PSS and 271826 KiB RSS. This is one device-list sample, not a background,
release, or streaming budget. Memory profiling and reduction remain future work.

Final debug APK SHA-256:
`dbcaebd4b8489fa9e65da2a098c56c34b06970346d47f3bf78e8952569fc17e7`.
