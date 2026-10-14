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
- Compose tests on Robolectric for UI behavior, with `androidx.compose.ui.test.junit4.v2.createComposeRule`. Look up expected text from `strings.xml` with `getString`; don't hard-code English. `AppLaunchTest` starts the real `MainActivity`. View model coroutines resume on the main looper after each network reply, and the v2 rule's `waitUntil` does not always run the looper once Compose is idle. Wait with `waitWithLooper` (`ComposeWaits.kt`) instead, and collect view model flows from a background scope in the test, as `LifecycleDialogsTest` does. A text field inside a dialog never lets Compose go idle under some `@Config` screen qualifiers, so keep the default size and scroll to rows instead.

`PinnedServer` (`data/TestServers.kt`) is a `MockWebServer` with a pinned self-signed certificate. `enqueue` answers requests in order. `route` answers by path, which suits loaders that poll several endpoints at once. Over HTTPS it speaks HTTP/2, which has no reason phrase, so put an error's text in the body's `message` field, as Proxmox does.

## Modules

- `core:api`: a plain Kotlin/JVM module with no Android dependencies. It holds the Proxmox client (`PveClient`), models, lenient JSON, TLS trust, the console websocket and the VNC client (`vnc/`). Keep Android types out of it so its tests run on the JVM against `MockWebServer`, including real TLS handshakes.
- `app`: the Android application. Background work uses WorkManager; the widget uses Glance.
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
- `GuestActions` runs power actions at app level, so a result is not lost when the screen changes, and reports outcomes on `events`. `GuestLifecycle` does the same for creation, snapshots, backups, restores, migrations, clones, templates and deletions, following each task on the node named in its UPID.
- `LifecycleViewModel` serves one guest's Snapshots and Backups screens and its lifecycle dialogs. `availableLifecycleItems` decides what the guest's menu offers.

**Navigation.** Navigation 3 (`ui/AppNavigation.kt`). The back stack is a list of `Route`s, saved across rotation and process death, and changed only through `Navigator`.
- `Route.Main` is always at the bottom. `MainScreen` holds the four top-level destinations (`TopLevelDestination`) as tabs in a `NavigationSuiteScaffold`. Back from another tab returns to Overview.
- Settings, sign-in and the terminal are routes pushed over `Main`. Each route gets its own `ViewModelStore`, cleared when the route is closed.
- Routes hold IDs, never data, because they are serialized with the back stack.

**View models.** One per feature: `ShellViewModel` (server menu, refresh, action messages), `ClusterViewModel` (Overview, Infrastructure), `GuestsViewModel` (list, detail, usage history, actions), `TasksViewModel` (list, log follower), `SearchViewModel` (search overlay), `AddServerViewModel` (sign-in flow). Polling and other data logic belong in the view model or a repository, not in composables. Guests and Tasks use Material 3 adaptive list-detail panes; the pane tells the view model what is selected.

**App lock.** `AppLock` holds the timing rules and has no Android dependencies, so it is unit-tested. `ProcessLifecycleOwner` drives it from `ProxKeepApplication`. While the app is locked, `AppRoot` composes only `LockScreen`, so nothing behind the lock fetches or shows data. `MainActivity` is a `FragmentActivity` because `BiometricPrompt` requires one.

**Configuration.**
- The long tail of settings is rendered from the server's own API schema. `ApiSchema` (core:api `schema/`) reads the API viewer's data file, `/pve-docs/api-viewer/apidata.js`, which the server publishes for its version. `SchemaRepository` downloads it once per server and version into the cache directory. A server without the file throws `SchemaUnavailableException`, which never signs the session out.
- `PropertyString` reads and writes composite values such as `virtio=BC:…,bridge=vmbr0`, keeping their key order. `check` validates a value against its `ParamSchema` the way the server will.
- `SchemaForm` is the plain, tested state of a schema-driven form: values, what changed, problems and the server's refusals by key. `SchemaFieldEditor` renders one setting by its type.
- `GuestConfigViewModel` serves a guest's settings screen. It reads the configuration when the screen opens and after each change, not on a timer, so an open form is never replaced. Every change carries the configuration's digest, so a change made elsewhere meanwhile is refused, not overwritten. Virtual machines apply changes as a task (POST); containers apply them at once (PUT).
- Hardware is edited by hand (`ui/config/Hardware.kt`, `HardwareDialogs.kt`); `HardwareFormats` covers its property strings without the schema. Options come from the schema, minus the keys `hardwareKeys` lists.
- The create wizards (`ui/create/`) are hand-designed. `VmDraft` and `CtDraft` check each step and build the creation parameters; they are plain and tested.

**Nodes, storage and backups.**
- `NodeViewModel` serves a node's screen (`ui/nodes/`): each tab's data is its own `StateFlow`, so a part loads only while its tab is on screen. Power actions, service actions and network changes run through one `run` that follows the task and reports on `messages`.
- `SchemaEditScreen` (`Route.SchemaEdit`) edits or creates any API object from its method in the schema: `PUT` reads the object first, `POST` starts from presets. It sends only what changed, with the object's digest. Use it for long-tail forms, such as network interfaces, storage configuration and backup jobs. `PveClient.read` and `write` reach any path for it.
- `StorageTransfers` runs uploads and downloads at app level, like `GuestLifecycle`, so they outlive the screen; they end with the process. Uploads stream from a `ContentResolver` in `PveClient.upload`, with no write timeout.
- `BackupJobsViewModel.runPlan` runs a job now on each node that holds its guests, because a backup runs where its guests are. `GuestLifecycle.restoreAsNew` and `backupKind` serve restoring a backup beside its guest.

**Datacenter.**
- `DatacenterScreen` lists the datacenter's sections (`DATACENTER_GROUPS`). Each entry names a schema path, and entries the server's schema lacks are hidden; all show while the schema is unknown.
- Most sections are collections described in `Collections.kt`: list path, ID key, item path and display keys, all schema templates such as `/access/users/{userid}/token/{tokenid}`. `CollectionScreen` and `CollectionViewModel` list, delete and act on them for any entry; editing and creating open `SchemaEditScreen`. Add a section by adding a `CollectionSpec`, not a screen.
- Path values go through `CollectionSpec.fill`, which encodes slashes with `encodePathValue`; `PveClient` decodes each segment, so `10.0.0.0/8` stays one segment.
- Permissions have no item paths, so they are removed with `PUT /access/acl` and `delete=1` (`aclRemoval`). A write that returns a secret, such as a new API token, shows it once (`SchemaEditViewModel.secret`).
- Ceph's status is hand-designed (`CephScreen`), since its JSON changes between Ceph releases; `CephStatus.fromJson` reads what is there.

**Background work.**
- `StatusWorker` (WorkManager) runs while alerts are on or a widget or tile is placed. It checks every saved server it can reach without the person (`ServerRepository.backgroundClient`: the session's client, an API token, or a saved password without a second factor) and records the signed-in server's `StatusSnapshot`. A saved password rejected in the background is forgotten, as on sign-in.
- `AlertChecker` is plain and tested: the first check of a server only records what it sees; later ones report failed tasks and nodes going offline or coming back. `AlertNotifier` keeps details off the lock screen.
- `StatusSurfaces` holds the snapshot for `StatusWidget` (Glance) and `StatusTileService`. Both hide server details while the app lock is on.
- `PendingNavigation` also carries a destination, a search request and a guest from notifications and app shortcuts. Recently opened guests become dynamic shortcuts (`GuestShortcuts.kt`).

**Remote desktop.**
- `RfbClient` (core:api `vnc/`) speaks the VNC protocol: versions 3.3 to 3.8, no authentication or VNC passwords, and the Raw, CopyRect and ZRLE encodings, with resizes and a client-drawn pointer. `VncConnection` carries it over the server's `vncwebsocket` relay, with the bytes in binary messages; `PveClient.openDesktop` asks for a VNC proxy with a generated password first.
- `DesktopManager` holds at most one `DesktopSession` at the app level, like the consoles. `DesktopScreen` draws the framebuffer into a bitmap, copying only the changed region.
- Input rules are plain, tested classes: `GestureInterpreter` turns touches into pointer actions for both pointer modes, `Viewport` does fit, zoom and pan, `DesktopController` applies them and the sticky modifiers, and `Keysyms` maps Android keys to X11 keysyms. Keep this logic out of the composable.

**Consoles.**
- `PveClient.openConsole` asks the server for a terminal proxy, then connects through its websocket relay.
- `ConsoleChannel` implements the pve-xtermjs framing: a ticket handshake, then `OK`, data and resize frames, and a 30 s keep-alive. The console websocket has no read timeout, because idle shells are quiet.
- `ConsoleManager` holds the open `ConsoleSession`s and the one on screen, at the app level, so they survive rotation, the lock and leaving the terminal. Switching servers or signing out closes them all.
- `ConsoleService` is a foreground service (special-use type) that runs exactly while a console is open, so consoles stay connected in the background. `AppContainer` starts it with the first console; it stops itself after the last. Its notification opens the terminal through `PendingNavigation`.
- The extra-keys row (`console/TerminalKey.kt`), text size, font and color scheme (`console/TerminalAppearance.kt`) are settings in `AppSettings`. Termux keeps one color scheme for all terminals; `ConsoleManager.refreshColors` repaints open ones.

## Conventions

- User-facing text lives in `app/src/main/res/values/strings.xml`. Use `<plurals>` for counts. `UiMessage` carries string resources out of the view model.
- Write user-facing text, commit messages and pull request descriptions in plain, short sentences. Commits have an imperative subject and a body explaining why. Pull request bodies have the sections "What's included", "Checks", "How to test" and "Known limits".
- Status colors are always paired with an icon or label (`ui/theme/StatusColors.kt`). Graceful shutdown stays visually and spatially separate from stop and reset, which ask for confirmation.
- Charts (`ui/components/UsageChart.kt`) show one measure on one scale, at most two series, in the fixed colors from `ui/theme/ChartColors.kt`, with a text description for screen readers. Never put two scales on one chart.
- Proxmox returns loosely typed JSON, such as `0`/`1` booleans and numbers as strings. Use the serializers in `core/api/json/LenientSerializers.kt` for new model fields.
- Licensing: the app is GPLv3 because the Termux code is GPLv3 only. ProxKeep's own code is GPLv3-or-later. Don't use the Proxmox name or logo as the app's name or icon.

## Where the code differs from the spec

- The API client is hand-written, not generated from the Proxmox schema. Schema-driven forms read the schema from the signed-in server at runtime instead, so they match its version.
- Termux's terminal libraries are GPLv3 only, not Apache 2.0 as the spec says. See the license note above.
- The remote desktop uses a VNC client written in Kotlin in core:api, not LibVNCClient. It speaks the protocol over the websocket directly, so the spec's local socket bridge and the NDK build are not needed.

## Milestone progress

**Milestone 1, Foundation.** Done in code. Server profiles, password and TOTP sign-in, API tokens, web sign-in for security keys and OpenID Connect, reverse proxy headers and client certificates, opt-in saved passwords, TLS pinning, app lock, Overview, the guest list and detail with usage charts, search, power actions, task tracking, and hiding actions the user lacks permission for.

Not yet confirmed against a real server:
- Console with an API token.
- Web sign-in. Security keys in the WebView need the server's domain to publish Digital Asset Links for the app. Without that, a security-key account signs in with a password and code, or an API token.

VNC over the websocket, the third prototype, is built: see milestone 3.

**Milestone 2, Terminal.** Done in code: the websocket session layer, several consoles at once, a foreground service that keeps them connected, a customizable extra-keys row with key repeat and function keys, hardware keyboard, pinch zoom, font and color scheme settings, selection, copy and paste. A long editing session against a real server still needs checking by hand.

**Milestone 3, Remote desktop.** Done in code: the VNC client, touch and trackpad pointer modes, zoom, the extra-keys bar, hardware keyboard and mouse, clipboard both ways, immersive mode and picture-in-picture. Not yet confirmed against real Windows and Linux guests. Not done: external displays, pointer capture for a mouse, and QEMU's extended key events for keyboard layouts other than the server's.

**Milestone 4, Guest lifecycle.** Done in code: snapshots, backup now, restore, migrate, clone, convert to template and delete. Restoring as a new guest and backup jobs come with milestone 6. The first public release follows this milestone; it needs testing against real servers first.

**Milestone 5, Configuration.** Done in code: schema-driven options, hand-designed hardware editing (processor, memory, disks, CD and DVD drives, network devices), cloud-init settings, pending changes, and wizards for new virtual machines and containers. Not yet confirmed against a real server. Moving disks between storages is not done.

**Milestone 6, Nodes, storage and alerts.** Done in code: node management (summary and history, updates with an upgrade console, services, network interfaces, DNS and time, certificates, disks and SMART, the system journal, reboot and shutdown), the storage browser with uploads, downloads from a URL and the template catalog, storage configuration, backup jobs, restoring as a new guest, background alerts, a status widget, a Quick Settings tile and app shortcuts. Not yet confirmed against a real server. Transfers stop if Android ends the app's process; a foreground service would keep them going. The network tab does not show the pending configuration's difference.

**Milestone 7, Datacenter.** Done in code: cluster options, access control (users, API tokens, groups, pools, roles, permissions, realms), the datacenter firewall, high availability, replication, SDN, Ceph status and pools, and notifications. Not yet confirmed against a real server. Not done: two-factor management, node and guest firewalls, Ceph OSD and monitor management, and a summary of what applying SDN changes.

**Milestones 8 and 9.** Not started.
