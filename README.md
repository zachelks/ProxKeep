# ProxKeep

A native Android client for administering Proxmox VE servers, built with
Kotlin, Jetpack Compose and Material Design 3.

This project is not affiliated with or endorsed by Proxmox Server Solutions
GmbH. Proxmox is a registered trademark of Proxmox Server Solutions GmbH.

## Status

Early development. The app can:

- add a server, check its certificate, and sign in with a password
  (including TOTP) or an API token
- list nodes, guests and storage
- show a guest's status, usage and configuration, and start, shut down,
  reboot, pause, resume, stop or reset it
- list recent and running tasks and follow a task's log
- open a terminal on a node, in a container, or on a virtual machine's
  serial port
- lock itself behind the device's fingerprint, face or screen lock

## Modules

| Module | Purpose |
| --- | --- |
| `app` | The Android application: theme, navigation shell, screens |
| `core:api` | Proxmox API client, models and JSON parsing. Plain Kotlin, no Android dependencies |
| `terminal:emulator`, `terminal:view` | Terminal emulation and the view that draws it, from Termux. See [terminal/README.md](terminal/README.md) |

## Building

Requirements: JDK 17 or newer and the Android SDK (API 37 platform). Android
Studio installs both.

```sh
./gradlew :app:assembleDebug     # build a debug APK
./gradlew :core:api:test         # run the API module's unit tests
./gradlew :app:testDebugUnitTest # run the app module's unit tests
./gradlew :app:lintDebug         # run Android lint
```

Every push runs these in GitHub Actions. The debug APK is attached to each
workflow run as the `app-debug` artifact.

## License

ProxKeep is free software under the GNU General Public License, version 3.
See [LICENSE](LICENSE).

ProxKeep's own code may also be used under any later version of that license.
The terminal modules in `terminal/` come from Termux and are licensed under
version 3 only, which is why the app as a whole is distributed under version 3.
See [terminal/README.md](terminal/README.md).
