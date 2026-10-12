# Terminal modules

The two modules in this directory are taken from
[Termux](https://github.com/termux/termux-app), at commit
`8629e632fcb95da272221be327db653fb24befe9` (26 September 2026).

| Module | Upstream directory | Purpose |
| --- | --- | --- |
| `emulator` | `terminal-emulator` | Terminal emulation: escape sequences, screen buffer, scrollback, key codes |
| `view` | `terminal-view` | The Android view that draws the terminal and handles touch, keyboard input and text selection |

## License

Termux is released under the GNU General Public License, version 3 only.
These modules also contain code from
[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator),
which is released under the Apache License 2.0. See the upstream
[`LICENSE.md`](https://github.com/termux/termux-app/blob/master/LICENSE.md).

## Changes from upstream

Everything is unmodified except for the following.

- `emulator/.../TerminalSession.java` is rewritten. Upstream starts a local
  process and talks to it through a pseudo-terminal. This version sends and
  receives the terminal's bytes through a `Transport` interface that the app
  implements, so a session can be a shell on a remote server. The methods
  that `TerminalView` and `TerminalSessionClient` use keep their names and
  meaning.
- `emulator/.../JNI.java` and the native code under `src/main/jni` are
  removed. They only served the local process.
- The Gradle build files are replaced with ones that fit this project.

## Updating

To take a newer upstream version, copy `terminal-emulator/src` and
`terminal-view/src` over these modules again, delete `JNI.java` and the `jni`
directory, restore `TerminalSession.java` from this repository, and update the
commit above.
