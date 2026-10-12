# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

ProxKeep is a native Android client for administering Proxmox VE (Kotlin, Jetpack Compose, Material 3). The product spec, with the milestone plan, is [docs/spec.md](docs/spec.md).

## Commands

Requires JDK 17+ and the Android SDK (API 37 platform). CI uses JDK 21.

```sh
./gradlew :app:assembleDebug                  # debug APK
./gradlew :core:api:test                      # API module tests (plain JVM)
./gradlew :app:testDebugUnitTest              # app unit tests
./gradlew :terminal:emulator:testDebugUnitTest  # Termux's emulator tests
./gradlew :app:lintDebug                      # Android lint

# A single test class or method
./gradlew :core:api:test --tests 'io.github.zachelks.proxkeep.core.api.PveClientTest'
./gradlew :app:testDebugUnitTest --tests '*AppLockTest.staysUnlockedForAShortAbsence'
```

CI (`.github/workflows/ci.yml`) runs all of the above on every push. `.github/scripts/report_results.py` repeats test failures, compiler errors and lint findings as GitHub annotations, which can be read through the GitHub API when raw logs can't. The debug APK is uploaded as the `app-debug` artifact.

Cloud sessions have no Android SDK, so CI is the only build check there. Nothing has an instrumented test or emulator setup. Behavior on a device and against a real server is checked by hand, which is why every pull request ends with a "How to test" list.

## Modules

- `core:api`: a plain Kotlin/JVM module with no Android dependencies. It holds the Proxmox client (`PveClient`), models, lenient JSON, TLS trust and the console websocket. Keep Android types out of it so its tests run on the JVM against `MockWebServer`, including real TLS handshakes.
- `app`: the Android application.
- `terminal:emulator` and `terminal:view` are vendored from Termux. They are unmodified apart from a rewritten `TerminalSession.java`, which uses a `Transport` instead of a local process. Read `terminal/README.md` before you touch them. Keep changes out of these modules where possible, so upstream updates stay a copy-over.

## Architecture

**Dependency wiring.** There is no DI framework. `ProxKeepApplication.AppContainer` builds the process-lifetime objects: `ServerRepository`, `ConsoleManager`, `AppSettings`, `AppLock`, `Authenticator`, and one shared `OkHttpClient`. Composables reach them with `appContainer()`, and `MainViewModel.Factory` pulls them from the container.

**Sessions and credentials.** `ServerRepository` owns the saved profiles (`servers.json` in `filesDir`) and the current `Session` (`None` / `SignedOut` / `Active(profile, client)`).
- `PveClient` is immutable. `withCredentials` returns a new client, and ticket renewal (every 30 minutes) swaps the client inside `Session.Active`. Always read the client from the current session; never cache it.
- Login tickets live only in memory. API token secrets are encrypted by `SecretBox` with an Android Keystore AES-GCM key.
- When the server rejects credentials (`PveApiException.isAuthenticationFailure`), call `repository.sessionExpired(profileId)`.

**TLS.** `ServerTrust` accepts a certificate only if the platform trusts it or its SHA-256 fingerprint matches the profile's pin. Each server gets a copy of the shared OkHttp client carrying its own trust settings. `probeServer` makes first contact and reports an untrusted certificate rather than sending anything. Never add a trust-all path.

**UI state.** Navigation uses no library.
- `AppShell` holds the top-level destination (an enum, `TopLevelDestination`) and shows overlays: the add-server flow, Settings, the terminal.
- One `MainViewModel` serves all screens. It holds cluster state, guest detail, tasks and action events.
- Live data is polled every 10 s while the app is visible. A single `/cluster/resources` call feeds the lists.
- Power actions return a task UPID, and `awaitTask` follows it to completion.
- Guests and Tasks use Material 3 adaptive list-detail panes.

**App lock.** `AppLock` holds the timing rules and has no Android dependencies, so it is unit-tested. `ProcessLifecycleOwner` drives it from `ProxKeepApplication`. While the app is locked, `AppRoot` composes only `LockScreen`, so nothing behind the lock fetches or shows data. `MainActivity` is a `FragmentActivity` because `BiometricPrompt` requires one.

**Consoles.**
- `PveClient.openConsole` asks the server for a terminal proxy, then connects through its websocket relay.
- `ConsoleChannel` implements the pve-xtermjs framing: a ticket handshake, then `OK`, data and resize frames, and a 30 s keep-alive. The console websocket has no read timeout, because idle shells are quiet.
- `ConsoleManager` holds one `ConsoleSession` at the app level, so it survives rotation and the lock. Switching servers or signing out closes it.

## Conventions

- User-facing text lives in `app/src/main/res/values/strings.xml`. Use `<plurals>` for counts. `UiMessage` carries string resources out of the view model.
- Write user-facing text, commit messages and pull request descriptions in plain, short sentences. Commits have an imperative subject and a body explaining why. Pull request bodies have the sections "What's included", "Checks", "How to test" and "Known limits".
- Status colors are always paired with an icon or label (`ui/theme/StatusColors.kt`). Graceful shutdown stays visually and spatially separate from stop and reset, which ask for confirmation.
- Proxmox returns loosely typed JSON, such as `0`/`1` booleans and numbers as strings. Use the serializers in `core/api/json/LenientSerializers.kt` for new model fields.
- Licensing: the app is GPLv3 because the Termux code is GPLv3 only. ProxKeep's own code is GPLv3-or-later. Don't use the Proxmox name or logo as the app's name or icon.

## Where the code differs from the spec

- The API client is hand-written, not generated from the Proxmox schema.
- Termux's terminal libraries are GPLv3 only, not Apache 2.0 as the spec says. See the license note above.

## Milestone progress

**Milestone 1, Foundation.** Mostly done. Server profiles, password and TOTP sign-in, API tokens, TLS pinning, app lock, Overview, the guest list and detail, power actions and task tracking all work.

Still missing from milestone 1:
- WebAuthn and OpenID Connect web sign-in
- custom headers and client certificates for reverse proxies
- opt-in saved passwords
- hiding actions the user lacks permission for
- global search
- usage charts

Of the three prototypes the spec puts first:
- Console with an API token: built, but not yet confirmed against a real server.
- WebAuthn in an in-app browser: not started.
- VNC over the websocket: not started.

**Milestone 2, Terminal.** In progress. Done: the websocket session layer, the extra-keys row with sticky modifiers, hardware keyboard, pinch zoom, selection, copy and paste.

Still missing from milestone 2:
- several sessions at once
- a foreground service to keep consoles alive in the background
- a customizable extra-keys row with key repeat
- font and color scheme settings

**Milestones 3 to 9.** Not started.
