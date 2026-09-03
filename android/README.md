# DeepSeek Harness for Android

An Android app that runs the harness **on the device**: a bundled
Node-for-Android binary executes `dsh web` against a JavaScript payload
unpacked from the APK, and a WebView displays the local UI.

There is no remote server. Everything — the agent loop, the tools, the
session store — runs inside the app's own private data directory.

## How it fits together

```
APK
├── lib/arm64-v8a/libnode.so     Node CLI (an ELF executable, not a library)
└── assets/payload.zip           installed @deepseek-ai/dsh + node_modules

first launch
└── unpack payload.zip  ->  filesDir/runtime/
    seed profile        ->  filesDir/dsh-home/profiles/web/
    spawn               ->  libnode.so runtime/…/dsh/lib/bin.js web --port 3080
    WebView             ->  http://127.0.0.1:3080/?token=…
```

`NodeService` is a foreground service, so a model turn keeps running when the
screen is off. `MainActivity` polls for the tokenized URL the harness prints
on a successful boot and then hands the screen to the WebView.

## Building

The APK is built by the **Android APK** GitHub Actions workflow
(`.github/workflows/android-apk.yml`). Run it from the Actions tab, or:

```sh
gh workflow run android-apk.yml -f dsh_version=alpha
```

It produces `deepseek-harness-debug.apk` and `deepseek-harness-release.apk`
as build artifacts and as a GitHub prerelease.

To build locally you need JDK 17, the Android SDK, and Node 22:

```sh
cd android
./scripts/fetch-node.sh        # stage libnode.so into jniLibs
./scripts/prepare-payload.sh   # build assets/payload.zip (~33 MB)
gradle wrapper --gradle-version 8.7
./gradlew assembleRelease
```

## Installing

Only **arm64-v8a** is built, which covers essentially every phone from the
last several years. Enable "install unknown apps" for your browser or file
manager, then open the downloaded APK.

The release APK is signed with the Android **debug key**. That is fine for
side-loading onto your own device and is not publishable to a store. To sign
with your own key, set `DSH_KEYSTORE_PATH`, `DSH_KEYSTORE_PASSWORD`,
`DSH_KEY_ALIAS`, and `DSH_KEY_PASSWORD` in the build environment.

First launch unpacks ~33 MB of JavaScript and then cold-starts Node, which
takes tens of seconds on a phone. Later launches skip the unpack.

## Configuring a model

Open the app, go to settings, and add an API key for your provider. Requests
go straight from the phone to the provider.

## What differs from the desktop harness

The composition in `payload-patches/cordis.patch.yml` departs from the stock
`web` profile in ways Android forces:

| Area | Desktop | Android | Why |
|---|---|---|---|
| Sandbox | `dsh-sandbox-local` + Landlock | disabled | No Landlock or seccomp runner is reachable from an app uid. The Android app sandbox confines the whole process instead. |
| Permission presets | `dsh-permission-presets` | disabled | It refuses to compose over an unconfined bash executor, which is what remains once the sandbox chain is out. |
| Bash executor | `dsh-bash-sandbox` | `dsh-bash-local` | The confining executor needs the sandbox capability. |
| Terminals | real PTY via `node-pty` | pipe-backed shim | node-pty's prebuilt binaries are glibc/mach-o/PE; none load under bionic. Commands run, but with no tty semantics. |
| FFI (`koffi`) | native addon | stub | Used only to call Win32; unreachable here, but imported at module scope so the import must still resolve. |
| Image attachments (`sharp`) | libvips | stub that refuses | sharp publishes no Android build. Non-image attachments are unaffected. |
| Patch reload | `live` | `startup` | Live reload pulls in HMR, which requires `--expose-internals`, a flag Node rejects from `NODE_OPTIONS`. |

**The agent runs unconfined within the app.** It cannot touch other apps'
data — Android's own sandbox prevents that — but it can freely read and write
everything inside the app's private directory, including its own session
store and API keys. Treat it the way you would treat `danger-full-access`
on a desktop.

## Layout

```
android/
├── app/src/main/java/ai/deepseek/harness/
│   ├── MainActivity.kt       shell UI, status screen, WebView
│   ├── NodeService.kt        foreground service running the Node child
│   ├── NodeBinary.kt         locates libnode.so in the native lib dir
│   └── PayloadInstaller.kt   unpacks assets/payload.zip, seeds the profile
├── payload-patches/          profile patch + the pure-JS native-module stubs
└── scripts/
    ├── fetch-node.sh         download Node-for-Android into jniLibs
    └── prepare-payload.sh    npm install + patch + zip into assets
```

## Why `libnode.so`

Since API 29 Android refuses to `execve()` a file in an app-writable
directory. Files packaged under `lib/<abi>/` are the documented exception:
the installer extracts them into a read-only, executable location. So the
Node CLI ships under a `.so` name even though it is a plain executable — the
name is what makes the packager and installer treat it correctly.

## Node source

Upstream Node publishes no Android binaries. The build pulls from
[`Towartz/nodejs-arm`](https://github.com/Towartz/nodejs-arm), which
cross-compiles official Node sources with the Android NDK. Pin a different
build with `NODE_RELEASE_TAG` and `NODE_ASSET`.
