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

Cloud sessions have JDK 21 and an Android SDK (`ANDROID_HOME=/opt/android-sdk`, API 37 platform), so all of the commands above run there too. Gradle downloads the build-tools it needs. On a cold cache, Maven Central sometimes answers with HTTP 429. Re-run the build when that happens; Gradle keeps what already downloaded, and `--max-workers=2` makes it less likely. Robolectric downloads its Android image from Maven Central on the first test run, so that run can hit the same limit.

Nothing has an instrumented test or emulator setup. Behavior on a device and against a real server is checked by hand, which is why every pull request ends with a "How to test" list.

## Tests

App tests are JVM unit tests in `app/src/test`. Choose the lightest kind that works:

- Plain JVM for code without Android types: repositories, view models, `AddServerState`, `Navigator`, `AppLock`. View model tests use `MainDispatcherRule`, which moves the main dispatcher to background threads. Cancel `viewModelScope` in teardown. Don't use the rule in Compose tests: Compose needs the real main thread, which Robolectric provides.
- Robolectric (`@RunWith(RobolectricTestRunner::class)`) for code that needs the Android framework or the app's resources: `ProfileStore`, `AppSettings`, the console classes, and anything that takes a `Context`.
- Compose tests on Robolectric for UI behavior, with `androidx.compose.ui.test.junit4.v2.createComposeRule`. Look up expected text from `strings.xml` with `getString`; don't hard-code English. `AppLaunchTest` starts the real `MainActivity`.

`PinnedServer` (`data/TestServers.kt`) is a `MockWebServer` with a pinned self-signed certificate. `enqueue` answers requests in order. `route` answers by path, which suits loaders that poll several endpoints at once. Over HTTPS it speaks HTTP/2, which has no reason phrase, so put an error's text in the body's `message` field, as Proxmox does.

## Modules

- `core:api`: a plain Kotlin/JVM module with no Android dependencies. It holds the Proxmox client (`PveClient`), models, lenient JSON, TLS trust and the console websocket. Keep Android types out of it so its tests run on the JVM against `MockWebServer`, including real TLS handshakes.
- `app`: the Android application.
- `terminal:emulator` and `terminal:view` are vendored from Termux. They are unmodified apart from a rewritten `TerminalSession.java`, which uses a `Transport` instead of a local process. Read `terminal/README.md` before you touch them. Keep changes out of these modules where possible, so upstream updates stay a copy-over.

## Architecture

**Dependency wiring.** There is no DI framework. `ProxKeepApplication.AppContainer` builds the process-lifetime objects: `ServerRepository`, `ClusterRepository`, `ServerAccessRepository`, `GuestActions`, `RefreshSignal`, `ConsoleManager`, `AppSettings`, `AppLock`, `Authenticator`, and one shared `OkHttpClient`. Screens get them with `appContainer()` and create their view models with `viewModel { SomeViewModel(container.…) }`.

**Sessions and credentials.** `ServerRepository` owns the saved profiles (`servers.json` in `filesDir`) and the current `Session` (`None` / `SignedOut` / `Active(profile, client)`).
- Every request for the signed-in server goes through `ServerRepository.request { client -> … }`. It is the one place that handles rejected credentials (it signs the session out), and it throws `NotSignedInException` when nothing is signed in. Don't read `Session.Active.client` directly for requests.
- `activeProfileId` changes only when the server or sign-in changes, not on ticket renewal. Loaders `flatMapLatest` on it, so a server switch cancels in-flight loads and data from one server is never shown under another.
- `PveClient` is immutable. `withCredentials` returns a new client, and ticket renewal (every 30 minutes) swaps the client inside `Session.Active`.
- Login tickets live only in memory. API token secrets, reverse proxy header values and opt-in saved passwords are encrypted by `SecretBox` with an Android Keystore AES-GCM key.
- `Session.SigningIn` means the app is signing in with a saved password, on start or after the session expired. A password the server rejects is forgotten at once, so retries never lock an account. Signing out forgets it too.
- Sign-in methods (`AuthMethod`): password with TOTP or recovery codes, API token, and `Web`, the server's own login page in a WebView (`ui/addserver/WebSignIn.kt`) for security keys and OpenID Connect. The app takes the page's `PVEAuthCookie`, renews it once to get a CSRF token, and clears the WebView's cookies.
- Reverse proxies: a profile can carry extra headers and a KeyChain client certificate alias. `ConnectionOptions` (core:api) adds the headers to every request, including the console websocket, without replacing the session's own credentials. `ServerTrust` presents the client certificate.
- `ServerRepository` depends on the `ProfileStorage` and `SecretCipher` interfaces (`data/Storage.kt`), so it and the loaders are unit-tested on the JVM against `PinnedServer` (see Tests above).

**TLS.** `ServerTrust` accepts a certificate only if the platform trusts it or its SHA-256 fingerprint matches the profile's pin. Each server gets a copy of the shared OkHttp client carrying its own trust settings. `probeServer` makes first contact and reports an untrusted certificate rather than sending anything. The web sign-in WebView applies the same rule in `onReceivedSslError`. Never add a trust-all path.

**Data loading.** Live data loads only while a screen collects it: loaders are `StateFlow`s built with `stateIn(…, WhileSubscribed(5 s))`, and screens collect with `collectAsStateWithLifecycle`, so nothing loads in the background or behind the app lock.
- `RefreshSignal.ticks()` paces every loader: at once, every 10 s, and whenever `request()` is called (the refresh button, a finished power action).
- `ClusterRepository` holds the one `/cluster/resources` call that feeds Overview, Guests, Infrastructure and search.
- `ServerAccessRepository` holds the server version and the credentials' permissions (`/access/permissions`). Use `ServerAccess.allows(paths, privilege)` to decide whether to offer an action; it allows everything while permissions are unknown, because the server enforces them anyway. `Permissions.guestPaths` includes the guest's pool.
- `GuestActions` runs power actions at app level, so a result is not lost when the screen changes, and reports outcomes on `events`.

**Navigation.** Navigation 3 (`ui/AppNavigation.kt`). The back stack is a list of `Route`s, saved across rotation and process death, and changed only through `Navigator`.
- `Route.Main` is always at the bottom. `MainScreen` holds the four top-level destinations (`TopLevelDestination`) as tabs in a `NavigationSuiteScaffold`. Back from another tab returns to Overview.
- Settings, sign-in and the terminal are routes pushed over `Main`. Each route gets its own `ViewModelStore`, cleared when the route is closed.
- Routes hold IDs, never data, because they are serialized with the back stack.

**View models.** One per feature: `ShellViewModel` (server menu, refresh, action messages), `ClusterViewModel` (Overview, Infrastructure), `GuestsViewModel` (list, detail, usage history, actions), `TasksViewModel` (list, log follower), `SearchViewModel` (search overlay), `AddServerViewModel` (sign-in flow). Polling and other data logic belong in the view model or a repository, not in composables. Guests and Tasks use Material 3 adaptive list-detail panes; the pane tells the view model what is selected.

**App lock.** `AppLock` holds the timing rules and has no Android dependencies, so it is unit-tested. `ProcessLifecycleOwner` drives it from `ProxKeepApplication`. While the app is locked, `AppRoot` composes only `LockScreen`, so nothing behind the lock fetches or shows data. `MainActivity` is a `FragmentActivity` because `BiometricPrompt` requires one.

**Consoles.**
- `PveClient.openConsole` asks the server for a terminal proxy, then connects through its websocket relay.
- `ConsoleChannel` implements the pve-xtermjs framing: a ticket handshake, then `OK`, data and resize frames, and a 30 s keep-alive. The console websocket has no read timeout, because idle shells are quiet.
- `ConsoleManager` holds one `ConsoleSession` at the app level, so it survives rotation and the lock. Switching servers or signing out closes it.

## Conventions

- User-facing text lives in `app/src/main/res/values/strings.xml`. Use `<plurals>` for counts. `UiMessage` carries string resources out of the view model.
- Write user-facing text, commit messages and pull request descriptions in plain, short sentences. Commits have an imperative subject and a body explaining why. Pull request bodies have the sections "What's included", "Checks", "How to test" and "Known limits".
- Status colors are always paired with an icon or label (`ui/theme/StatusColors.kt`). Graceful shutdown stays visually and spatially separate from stop and reset, which ask for confirmation.
- Charts (`ui/components/UsageChart.kt`) show one measure on one scale, at most two series, in the fixed colors from `ui/theme/ChartColors.kt`, with a text description for screen readers. Never put two scales on one chart.
- Proxmox returns loosely typed JSON, such as `0`/`1` booleans and numbers as strings. Use the serializers in `core/api/json/LenientSerializers.kt` for new model fields.
- Licensing: the app is GPLv3 because the Termux code is GPLv3 only. ProxKeep's own code is GPLv3-or-later. Don't use the Proxmox name or logo as the app's name or icon.

## Where the code differs from the spec

- The API client is hand-written, not generated from the Proxmox schema.
- Termux's terminal libraries are GPLv3 only, not Apache 2.0 as the spec says. See the license note above.

## Milestone progress

**Milestone 1, Foundation.** Done in code. Server profiles, password and TOTP sign-in, API tokens, web sign-in for security keys and OpenID Connect, reverse proxy headers and client certificates, opt-in saved passwords, TLS pinning, app lock, Overview, the guest list and detail with usage charts, search, power actions, task tracking, and hiding actions the user lacks permission for.

Not yet confirmed against a real server:
- Console with an API token.
- Web sign-in. Security keys in the WebView need the server's domain to publish Digital Asset Links for the app. Without that, a security-key account signs in with a password and code, or an API token.

Of the three prototypes the spec puts first, VNC over the websocket is not started.

**Milestone 2, Terminal.** In progress. Done: the websocket session layer, the extra-keys row with sticky modifiers, hardware keyboard, pinch zoom, selection, copy and paste.

Still missing from milestone 2:
- several sessions at once
- a foreground service to keep consoles alive in the background
- a customizable extra-keys row with key repeat
- font and color scheme settings

**Milestones 3 to 9.** Not started.
