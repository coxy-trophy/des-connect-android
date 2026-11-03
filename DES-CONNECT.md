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
COPYING, LICENSES and dependency-license generation are retained. Existing artwork
and many in-app strings remain upstream in this first step; a full visual redesign
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
