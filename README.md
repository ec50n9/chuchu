<h1 align="center">Roost</h1>

<p align="center">Your sessions stay on the server. This app is only the remote control.</p>

<p align="center">A native Android client for tmux / zmx on top of libghostty.</p>

---

> **Fork of [jossephus/chuchu](https://github.com/jossephus/chuchu)** (MIT, see [LICENSE](./LICENSE)).
> Roost diverged at the commit that removed the tab concept and is going its own way.
> The application id changed, so **Roost and Chuchu cannot be installed side by side and do
> not share data** — export an encrypted backup from Chuchu and import it into Roost.

## Why sessions, not tabs

A terminal session's lifetime lives on the server, not on the phone. tmux / zmx keep the
process alive; Roost only attaches back to it. So there is no tab concept here: the local app
holds no "session container", and the server list simply shows the sessions the server has.

Staying connected is two layers stacked, and one alone is not enough:

- **mosh** — roaming and network changes (transport layer)
- **multiplexer (tmux / zmx)** — the process keeps running even if the app is killed (process layer)

Plain SSH (no multiplexer) is a fallback for first contact and emergencies, not the main path.

## Features

- tailscale, ssh password + key authentication
- session-first server list: each host card lists its remote sessions, jump straight into one
- mosh for roaming, tmux / zmx for persistent remote sessions
- more than 400 themes from the official ghostty repository
- configurable accessory keys and custom terminal actions
- per-host post-connect actions that autorun after SSH/Mosh connects or reconnects
- encrypted local import/export backups for SSH keys and server profiles
- image display using libghostty's kitty image protocol support
- terminal renderer with working resize, scrollback, focus, modifier keys and mouse actions

## Status

Daily driven and changing fast. The current priority is making "never lose a session" true in
practice — automatic reconnect and automatic re-attach when the app returns to the foreground.
See the roadmap in the repo's local planning notes.

## Getting Started

Download the APK from [releases](https://github.com/ec50n9/chuchu/releases).

- **Stable:** the latest non-prerelease build
- **Nightly:** every push to `main` publishes a rolling prerelease at the [`nightly`](https://github.com/ec50n9/chuchu/releases/tag/nightly) tag

## How Roost differs from upstream

- No tabs: `TerminalTabStrip`, `TerminalTabManager` and `NewTabHostPicker` are gone; navigation
  is session-based (`terminal/session/{sessionId}`), and the terminal screen switches sessions
  through one per-host switcher
- The server list is the session hub: new session, refresh and jump-in live on the host card
- New tmux sessions are named `roost-N`; sessions created before the rename (`chuchu-N`) are
  still recognized and reusable
- The backup file extension stays `chuchu-backup` on purpose, so backups exported from Chuchu
  remain importable
- Upstream bug fixes are cherry-picked when they apply. Roost does not send pull requests
  upstream and does not carry opt-in switches just to stay mergeable

## Stack

- Kotlin + Jetpack Compose for the Android app
- Zig for native build orchestration and JNI/native bridge code
- Ghostty VT for terminal emulation
- `libssh2` + `openssl` for the current native SSH path
- Room for local data storage

### Development - Prerequisites

If you have nix installed, the following three steps will get you started

1. nix develop - will set you up with everything you will need.
2. running 'make build' will build the native code needed
3. running 'make app' will build the apk and install it in a connected device.

If you don't have nix installed, you will need

1. setup tools
- Android Studio - This will set up the needed Android SDK, Android NDK and Java runtime (JDK 17+).
- Zig 0.15.2
2. build the native library

Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`, then build the JNI library for Android arm64:

```sh
zig build jni -Dtarget=aarch64-linux-android
```

That copies `libchuchu_jni.so` into `app/src/main/jniLibs/arm64-v8a/` (the `chuchu_jni` library
name is kept from upstream; it is internal to the JNI bridge).

3. From android studio run
```sh
./gradlew assembleDebug
```

For a manual debug APK, run the native build first, then assemble the app:

```sh
make build
cd android && ./gradlew assembleDebug
```

The APK should include `lib/arm64-v8a/libchuchu_jni.so`; without it native SSH/key generation will fail on device.

## Name

`Roost` — the place a bird comes back to. That is what this app is: you leave, the session stays,
you return and it is still there.

## Credits

Built on [jossephus/chuchu](https://github.com/jossephus/chuchu), which in turn came from the
author's wish for a native Android SSH client after using
[vvterm](https://github.com/vivy-company/vvterm) on iOS. The upstream name `chuchu` is a character
from the Amharic book [Yesinbit Kelemat](https://www.goodreads.com/book/show/30759971) — that story
is the upstream author's, and stays credited to them here.
