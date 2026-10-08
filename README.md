<div align="center">

# M3UAndroid

**A simple IPTV player for Android phones, tablets, and TV.**

[![Upstream Release](https://img.shields.io/github/v/release/oxyroid/M3UAndroid?label=upstream%20release)](https://github.com/oxyroid/M3UAndroid/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/github/license/oxyroid/M3UAndroid)](LICENSE)
[![Telegram](https://img.shields.io/badge/Telegram-Channel-26A5E4?logo=telegram&logoColor=white)](https://t.me/m3u_android)

</div>

---

M3UAndroid helps you watch IPTV streams from your own playlist sources.

**This project is based on [oxyroid/M3UAndroid](https://github.com/oxyroid/M3UAndroid).**
The original project and its contributors provide the Android IPTV player,
shared libraries, and playback engine. This repository maintains a customized,
simplified Android TV experience with a bundled IPTV playlist; the phone app
remains unchanged.

This version is maintained at [Miraculeux/M3UAndroid](https://github.com/Miraculeux/M3UAndroid).

## Source and upstream

Clone this repository, including its pinned upstream submodules:

```bash
git clone --recurse-submodules git@github.com:Miraculeux/M3UAndroid.git
cd M3UAndroid
```

To track the original project's updates alongside this repository:

```bash
git remote add upstream https://github.com/oxyroid/M3UAndroid.git
git fetch upstream
```

See [Simplified IPTV TV build](#simplified-iptv-tv-build) for this variant's
behavior and build instructions.

## Upstream downloads

These downloads are from the original project and do not include this
repository's customized TV behavior. Build this repository to obtain the
customized TV APK.

- [GitHub Release](https://github.com/oxyroid/M3UAndroid/releases/latest)
- [Nightly Build](https://nightly.link/oxyroid/M3UAndroid/workflows/android/master/artifact.zip)
- [Telegram Channel](https://t.me/m3u_android)

## Extensions

- [Extension documentation (English)](docs/extensions/README.md)
- [插件文档（简体中文）](docs/extensions/README.zh-CN.md)

## Build on macOS

Required tools:

- JDK 17.
- Gradle 9.3.1, supplied by the repository wrapper.
- Android SDK platform `platforms;android-37.0`, Build Tools `36.0.0`,
  and `platform-tools`.
- Git submodules at the revisions recorded in this repository:

```bash
git submodule update --init --recursive
```

Set `JAVA_HOME` to the JDK home and `ANDROID_HOME` to the SDK directory.
Alternatively, configure `sdk.dir` in the ignored `local.properties` file.

The wrapper downloads Gradle from Huawei Cloud and retains the upstream SHA-256
check. On the configured local Mac, JDK 17 was downloaded from the Tsinghua
Adoptium mirror. The SDK uses Google's official repository because the checked
domestic mirrors did not contain API 37.

The local shell configuration is in `~/.config/android-build/env.sh`. The local
`~/.gradle/init.d/m3uandroid.gradle` prepends Aliyun Google, Maven Central, and
Gradle Plugin mirrors for this checkout and its included builds only, retaining
the project's original repositories as fallback. These machine-local files are
not part of the repository.

Build phone and TV debug APKs on an 8 GB Mac with bounded memory and concurrency:

```bash
./gradlew :app:smartphone:assembleDebug :app:tv:assembleDebug \
  --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
```

Outputs are under `app/smartphone/build/outputs/apk/debug/` and
`app/tv/build/outputs/apk/debug/`.

The local checkout uses sparse checkout to skip obsolete `nextlib-0.8.5`
native-pack snapshots and store screenshots during setup. The current
`nextlib-1.9.1-0.10.1` native packs are required even for debug builds and must be
present. Before working with the excluded files, fetch the full working tree with
`git sparse-checkout disable`; partial-clone objects are fetched on demand.

## Simplified IPTV TV build

The TV app starts directly in a DPad-focused channel list imported from
[`app/tv/src/main/assets/iptv.m3u`](app/tv/src/main/assets/iptv.m3u). The bundled
snapshot contains 191 entries from the local `Iptv.m3u`, including alternate
sources for the same channel. No playlist URL, import action, favorites menu,
settings page, extension setup, or phone pairing is exposed in this TV UI.
The phone app is unchanged.

Press the remote's OK/Select button on a channel to start playback. Back or the
player's Close button stops playback and returns to the same list, restoring
the selected channel's focus and retaining list scroll state. Loading errors
show a retry action, and stream errors remain visible in the player.

The playlist is embedded, but its 188 LAN streams still require the Mac's UDPxy
service at `192.168.50.11:8888` and a reachable LAN connection. The three original
external HTTP/HTTPS streams are retained unchanged and require Internet access.
To update channels or change the Mac IP, replace the bundled M3U and rebuild the
TV APK. Merely editing the original file on the Mac does not update an installed
APK.

Validate the TV build:

```bash
./gradlew :app:tv:testDebugUnitTest :app:tv:assembleDebug \
  :app:tv:assembleDebugAndroidTest \
  --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
```

On a connected TV or emulator, run the real import, DPad focus, and playback
return tests with `:app:tv:connectedDebugAndroidTest` and
`-Pandroid.testInstrumentationRunnerArguments.class=com.m3u.testing.TvIptvNavigationTest`.

## License

M3UAndroid is an open-source project licensed under the [GNU General Public License v3.0](LICENSE).
