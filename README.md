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

The TV app imports its channels from
[`app/tv/src/main/assets/iptv.m3u`](app/tv/src/main/assets/iptv.m3u) (1080P preferred)
or [`app/tv/src/main/assets/iptv-4k.m3u`](app/tv/src/main/assets/iptv-4k.m3u)
(4K preferred). Each bundled
snapshot contains 150 unique channel names, curated from the 191 entries in the
local `Iptv.m3u`. Each name retains one source, preferring a source that produced
decoded media frames and then the selected resolution preference.
Equal-resolution sources retain their original order. CCTV1–CCTV17
occupy channel numbers 1–17; CCTV5+, the other central channels, provincial
satellite channels, and the remaining local/specialty/radio channels follow.
Numbers are the one-based bundled playlist positions, not database IDs, and
are displayed prominently on every grid card. No playlist URL, import action, favorites menu,
settings page, extension setup, or phone pairing is exposed in this TV UI.
The phone app is unchanged.

On launch, the app reads the saved quality preference and last played channel,
then goes directly to live playback without displaying the channel grid. A
neutral loading screen appears while importing. First launch, or a saved
channel no longer present in the list, automatically plays the first channel
(CCTV1). The last selected channel is saved in the existing Settings DataStore
under `tv_iptv_last_channel_title` whenever playback starts, including numeric
and Up/Down switching. It is matched by channel name, not database ID or source
URL, so changing resolution does not change the resumed channel. Merely moving
grid focus does not change the saved channel. Startup import/read failures show
the retry UI; retry still performs startup playback. Playback errors remain
visible rather than silently selecting another channel.

The button at the top right switches between **1080P preferred** (the default)
and **4K preferred**, with the choice saved in the existing Settings DataStore
under the TV-only `tv_iptv_prefer_4k` key. Each launch reads this setting before
importing the appropriate list. No separate APK or build flag is required.
1080P preferred selects the highest verified resolution at or below 1920×1080;
4K preferred selects a verified 3840-wide-or-higher source when available.
If the preferred resolution is unavailable, both modes retain the best verified
alternative, including a 4K exception in 1080P mode, rather than losing channels.
This is source selection, not transcoding or a limit on decoder output.
Both lists contain the same 150 names and channel numbers. Switching cancels
pending numeric input, reloads the list without autoplay, and restores the
previously focused channel by name even when its database ID changes. Loading
or saving errors show the retry UI and do not report the preference as changed.
The button is available only on the grid, not during playback.

Current probe evidence confirms separate 1080P/4K choices for CCTV16, 江苏卫视,
and 四川卫视. 湖南卫视 and several other satellite channels only had verified
4K sources; their other original addresses timed out. They therefore use the
same verified 4K source in both modes until working lower-resolution sources
are supplied. The 1080P-preferred list has eight such 4K channels; the
4K-preferred list has eleven.

During playback, Back or the player's Close button opens the DPad-focused
four-column grid. Press OK/Select on a channel to start playback. Back or the
player's Close button stops playback and returns to the same list, restoring
the selected channel's focus and retaining list scroll state. Loading errors
show a retry action, and stream errors remain visible in the player.
In the grid, Left/Right move between columns and Up/Down move between rows.
During playback, Up switches to the previous playlist entry and Down switches
to the next entry, wrapping between the first and last entries. Back/Close restores
focus to the currently playing entry, scrolling the grid to it when necessary.
During normal playback the floating controls disappear after three seconds
without input. OK/Select or Left/Right reveals them without pausing playback;
once visible, OK/Select activates the focused button. Pausing, buffering,
errors, and channel changes show the controls again. Up/Down channel switching
and Back to the grid continue to work when the controls are hidden.
On either page, standard remote digits 0–9 and numeric-keypad digits select by
channel number. The input appears in a non-focusable overlay. After 1.5 seconds
without another digit the selected channel starts; OK/Enter confirms immediately
without also pausing or activating a grid card. Back cancels the pending input
without leaving playback. Directional navigation cancels pending input before
performing its normal action. Leading zeros are accepted; zero, out-of-range
numbers, and input longer than the largest channel number show a temporary
error without interrupting playback. Pending selection is cancelled when the
activity stops, playback closes, or the playlist reloads.
The TV player keeps the screen awake while its playback page is visible,
including buffering, channel switches, and pauses. Returning to the grid
restores the view's previous keep-screen-on setting. This uses Android's
foreground-view mechanism, not a background wake lock or a device-wide
screensaver setting; Android stops honoring it when the app is in the background.

The playlist is embedded, but its 148 LAN streams still require the Mac's UDPxy
service at `192.168.50.11:8888` and a reachable LAN connection. The two retained
external HTTP/HTTPS streams require Internet access. Probing is a point-in-time
check, not automatic runtime source failover; unique channels without a verified
working source remain in the list and report playback errors when unavailable.
To update channels or change the Mac IP, update both bundled M3Us while retaining
identical name/order mappings and rebuild the
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

Build the optimized TV release:

```bash
./gradlew :app:tv:assembleRelease --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
```

The APK is written to
`app/tv/build/outputs/published-apk/release/tv-1.0.9.apk`. The current release
configuration uses the existing debug signing key; it is not a production
signing setup.

TV 1.0.3 fixes an Android 9 startup `VerifyError`: R8 had merged Netty's
runtime-guarded `VarHandleByteBufferAccess` methods into a class loaded by
`WindowCompat`. The targeted TV ProGuard rule keeps these methods isolated
without disabling optimization for the rest of the app.
Channel metadata orphan cleanup also avoids a `DELETE` table alias unsupported
by older system SQLite versions. This does not change the database schema or
which playlist's orphaned metadata is removed.
TV cards use a single focus target, expose focus in their accessibility labels,
and restore keyboard input mode before focusing the list, including after a
pointer click on the player's Close button.

When changing R8 rules or Netty dependencies, test the actual release APK on
Android 9 (API 28) as well as a newer Android TV device/emulator. Check the
startup autoplay, the bundled-channel list after Back, DPad selection,
playback, Back/Close focus restoration,
and repeated cold starts. Debug-only tests cannot detect this release regression.
An ARM64 Android 9 AOSP emulator checks OS compatibility, not Zidoo's Realtek
hardware or vendor firmware.

TV 1.0.3 validation: 35 TV unit tests, two metadata orphan-cleanup tests on
Android 9, and all three TV navigation instrumentation tests on Android 9
passed. The optimized release APK was also exercised on Android 9 ARM64 AOSP
and Android 14 ARM64 TV: 191-channel list, actual CCTV1 playback, and Back/Close
focus restoration passed without crashes. Android 9 passed three consecutive
cold starts. Zidoo Z9X hardware remains unverified.

TV 1.0.4 adds the four-column grid and wrapping Up/Down playback switching.
Validation includes 40 TV unit tests and six navigation tests covering card
bounds, row/column movement, switching, wrapping, and Back/Close focus restoration.
Android 13+ navigation tests refresh the accessibility cache before checking
focus on retained Compose nodes. The optimized release was also checked on
Android 9 ARM64 and Android 14 ARM64 TV for four-column layout, actual playback
and channel switching, and return focus; no crashes were observed.

TV 1.0.5 restores the keep-screen-on behavior missing from the Compose TV
video surface. Regression tests check that playback and channel switching keep
the screen awake, Back/Close restore normal idle behavior, and activity
recreation reapplies the setting to the new view. Zidoo's vendor screensaver
behavior still requires real-device confirmation.
All eight TV navigation/lifecycle tests passed on Android 9. With the emulator's
plugged-in stay-awake option disabled and screen timeout temporarily set to
10 seconds, the actual release kept CCTV1 playback awake for 25 idle seconds
and retained `KEEP_SCREEN_ON` when switching channels. Back removed the flag
and the grid allowed normal sleep after another 25 idle seconds. The emulator's
original settings were restored after validation.

TV 1.0.6 automatically hides the floating controls after three seconds of
normal playback without input, moving focus to a separate video-page target.
All ten Android 9 navigation/lifecycle tests passed, including the display
interval, remote reveal without pausing, hidden-page switching/Back, and
paused/buffering/error visibility. The optimized release was also checked with
actual CCTV1 playback: controls hid, OK revealed them, pause kept them visible,
resume hid them again, and hidden-page Down/Back restored the selected grid
entry. The keep-screen-on behavior remained active and no crashes were observed.

TV 1.0.7 adds numeric channel selection and the deduplicated, numbered list.
All 191 original sources were probed; unsuccessful probes were retried with a
longer decoding window and lower concurrency on a separate loopback-only proxy,
without changing the normal IPTV service. The selected 150 channels include
149 sources with decoded media frames. All CCTV1–CCTV17 sources were verified
at 1080p or above. Eleven selected sources are 4K. The only unverified selected
channel is `无锡生活`: its original external URL returns HTTP 404 and has no
alternate source in the original list, so it is retained rather than silently
removed. Availability and actual device codec support can change.
Validation: all 46 TV unit tests and all 14 Android 9 navigation/lifecycle tests
passed against the final playlist, including numeric debounce, immediate OK,
numeric-keypad input, cancellation, invalid-number feedback, background
cancellation, and return focus. An existing 191-channel installation was
upgraded without clearing app data and showed the new 150-channel list.
The optimized release was also checked for actual CCTV13 playback selected by
digits, timeout selection, cancellation, invalid-number feedback without
interrupting video, switching to CCTV12, and restoring grid focus. Controls
still auto-hide, `KEEP_SCREEN_ON` remains active, and the crash buffer was empty.
The APK signature and ZIP alignment passed validation, and its embedded M3U
matches the source asset. The Zidoo Z9X remote and hardware decoder still need
real-device confirmation.

TV 1.0.8 adds the persisted, per-APK resolution preference and matching playlists.
All 50 TV unit tests and all 15 Android 9 navigation/lifecycle tests passed,
covering default and saved modes, load/save errors, preserved channel numbering,
focus after switching sources, DPad access to the quality button, numeric
selection, and activity recreation/relaunch. The optimized release was checked
with actual CCTV16 playback: default 1080P requested `239.49.8.31:8000`,
4K requested `239.49.0.109:8000`, and switching back restored the 1080P address.
Both choices survived force-stop and a fresh process launch. The player still
hid its controls and kept the screen awake, and the crash buffer was empty.
Signature/ZIP alignment checks passed and both packaged playlists match their
source assets. No new dependencies or database schema changes are required.
Sony XR-55X91J and Zidoo Z9X hardware remain unverified.

TV 1.0.9 starts playback automatically using the last played channel and saved
quality mode, falling back to CCTV1 only when no matching channel is saved.
All 56 TV unit tests and all 16 Android 9 navigation/lifecycle tests passed,
including first launch, last-channel restoration without a grid, storage
read/write errors, startup retry, cancellation before delayed playback,
and all existing grid, numeric, quality, control-visibility, and screen-awake
behavior. The optimized release was exercised with actual CCTV13 playback after
force-stop/relaunch and Back restoring channel 13 focus. Changing the saved
quality to 4K then relaunching resumed CCTV16 via `239.49.0.109:8000`; reverting
to 1080P resumed the same channel via `239.49.8.31:8000`. Merely changing quality
on the grid did not autoplay. `KEEP_SCREEN_ON` remained active, the crash buffer
was empty, signature/ZIP alignment checks passed, and both M3U assets were
unchanged. Sony and Zidoo hardware remain unverified.

## License

M3UAndroid is an open-source project licensed under the [GNU General Public License v3.0](LICENSE).
