# Android client for Proxmox VE: product spec

Oct 10, 2026 · @Zach

## Summary

A native Android client for Proxmox, published on Google Play under the GPL. It covers the full administration feature set and adds a first-class terminal and remote desktop.

| Decision | Choice |
| --- | --- |
| Platform | Native Android: Kotlin, Jetpack Compose, Material Design 3 |
| Distribution | Public release on Google Play |
| License | GPL, source published (GPLv3 or later recommended) |
| Feature scope | Parity with the web interface, delivered in milestones |
| Authentication | Every commonly used sign-in method |
| Terminal | Quality comparable to a dedicated terminal app such as Termux |
| Remote desktop | Desktop interaction comparable to Microsoft's Windows remote desktop app |

## Scope

Proxmox VE is the core of the app. Backup Server and Datacenter Manager follow as later milestones.

- **Proxmox VE:** full administration. The current release is 9.2 (May 2026).
- **Proxmox Backup Server and Datacenter Manager:** separate APIs, added after the VE feature set. Current releases are 4.2 and 1.1.
- **Supported VE versions:** 8 and 9 proposed. Each older version adds testing cost.
- **Devices:** phones, tablets and foldables, plus keyboard and mouse use on large screens.

Proposed non-goals for now:

- Proxmox Mail Gateway
- SPICE consoles (audio and dynamic resolution)
- A general-purpose SSH client
- A hosted relay or cloud account service

## Authentication and connection

The app supports every sign-in method through three paths: a native form, a web sign-in, and API tokens.

| Method | How the app handles it |
| --- | --- |
| Password realms: Linux PAM, Proxmox VE authentication server, LDAP, Active Directory | Native form. The realm list is read from the server. |
| TOTP and recovery codes | Native second step after the password. |
| WebAuthn and security keys | Web sign-in: the server's own login page in an in-app browser. The app keeps the resulting session. |
| OpenID Connect | Web sign-in, same path. |
| API token | Token ID and secret entered once. Console access with tokens needs a prototype. |
| Reverse proxy in front of the server | Custom HTTP headers and client certificates per server profile. |

- **Server profiles:** any number of clusters or standalone nodes, with quick switching.
- **TLS trust:** system and user-installed certificate authorities are trusted. For a self-signed certificate the app shows the fingerprint, asks once, pins it, and warns if it changes. Validation is never switched off.
- **Sessions:** login tickets expire after about two hours (to verify). The app renews them silently while in use. Web sign-in sessions ask again once expired.
- **Credential storage:** secrets are encrypted with keys held in the Android Keystore. Saving a password is opt-in.
- **App lock:** biometric or device credential, with a configurable timeout.
- **Permissions:** actions the signed-in user cannot perform are hidden or disabled.

## Terminal

The terminal is built on Termux's emulator and view libraries, fed by Proxmox's websocket terminal channel instead of a local shell.

- **Where it opens:** node shells, container consoles, and VM serial consoles where the VM has a serial port.
- **Base:** the Termux `terminal-emulator` and `terminal-view` libraries, which are Apache 2.0 licensed. The main work is replacing their local-process session with a websocket-backed one.
- **Not used:** xterm.js in a WebView. It is faster to build but falls short on keyboard handling, selection and scrolling.

Features:

- Customizable extra-keys row (Esc, Tab, Ctrl, Alt, arrows, function keys) with sticky modifiers
- Full hardware keyboard support, including key combinations
- Pinch to change font size; choice of font and color scheme
- Text selection, copy and paste, with bracketed paste
- Scrollback, true color, mouse reporting, link detection
- Several sessions at once, switchable without disconnecting
- A foreground service that keeps sessions connected while the app is in the background

A node shell ends when its connection drops, so keep-alive and a clear reconnect path matter.

## Remote desktop

The remote desktop is a native VNC viewer built on existing GPL code, with the touch and pointer model of Microsoft's Windows remote desktop app.

- **Base:** LibVNCClient (GPL v2 or later) for the protocol. AVNC (GPL-3.0-or-later) is the reference Android client and already has configurable gestures, virtual keys, clipboard sync and picture-in-picture.
- **Main work:** carrying the VNC connection over Proxmox's authenticated websocket instead of a direct network socket, and fitting the viewer into the app.
- **Not used:** noVNC in a WebView. Its touch handling conflicts with the interaction model below.

Interaction model:

- Two pointer modes: direct touch, and a trackpad mode that moves an on-screen cursor
- Tap for left click, long-press or two-finger tap for right click, two-finger drag to scroll, double-tap and drag to drag
- Pinch to zoom and pan, with fit-to-screen and actual-size toggles
- Extra-keys bar: Ctrl, Alt, Win, Esc, Tab, function keys, and Ctrl+Alt+Del
- Hardware keyboard and mouse support, including pointer capture
- Immersive landscape mode, picture-in-picture, and external displays

Limits that come from VNC, which the app should explain where they apply:

- No audio.
- Resolution is set inside the guest and does not follow the device screen.
- Clipboard works only when the VM has the VNC clipboard enabled and SPICE guest tools installed. The app can check the VM's display setting and offer to change it.

## Feature map

The main flows get hand-designed screens; the long tail of settings is rendered from Proxmox's API schema.

| Area | Includes | Build approach |
| --- | --- | --- |
| Overview | Cluster health, quorum, node status, resource usage, recent tasks | Hand-designed |
| Guests | VM and container list, search, filters, status, usage charts, power actions | Hand-designed |
| Guest lifecycle | Snapshots, backup now, restore, migrate, clone, convert to template, delete | Hand-designed |
| Guest configuration | Hardware, options, cloud-init, network, resource limits | Hand-designed for hardware, schema-driven for options |
| Create wizards | New VM, new container | Hand-designed |
| Consoles | Terminal and remote desktop | Hand-designed |
| Nodes | Summary, updates, services, network, DNS and time, disks and SMART, certificates, system log, reboot and shutdown | Mixed |
| Storage | Storage list, content browser, ISO and template upload or download, storage configuration | Mixed |
| Backup | Backup jobs, schedules, retention, backup browser | Hand-designed |
| Datacenter | Firewall, high availability, replication, SDN, Ceph, notifications, cluster options | Schema-driven first, refined by hand |
| Access control | Users, groups, roles, permissions, realms, API tokens, two-factor setup | Schema-driven |
| Tasks and logs | Running tasks, history, live task log, cluster log | Hand-designed |
| Android integration | Alerts for failed tasks and offline nodes, home screen widgets, Quick Settings tile, app shortcuts | Hand-designed |
| Backup Server | Datastores, snapshots, verify, prune and garbage collection, sync jobs | Later milestone |
| Datacenter Manager | Remotes, cross-cluster overview, cross-cluster migration | Later milestone |

## Screen map and navigation

Every screen sits under one of four top-level destinations, and the consoles open full screen from a guest or a node.

&#91;embedded content: screen map · 4 destinations, 2 consoles\]

After sign-in the app lands on Overview. The terminal and remote desktop cover the whole screen and return to the guest or node they were opened from.

- **Phones:** a bottom navigation bar with the four destinations.
- **Tablets and foldables:** a navigation rail, with list and detail side by side.
- **Server switching:** a switcher in the top app bar, available from every destination.
- **Search:** one search across guests, nodes and storage.
- **Color:** dynamic color for the theme, plus fixed status colors that are always paired with an icon or label.
- **Destructive actions:** graceful shutdown is kept apart from stop and reset, and anything irreversible asks for confirmation.
- **Every screen:** designed loading, empty, error and unreachable states; edge-to-edge layout; predictive back.

## Architecture

A single-activity Compose app in layered modules, with its API client generated from Proxmox's published API schema.

| Concern | Choice |
| --- | --- |
| UI toolkit | Jetpack Compose with Compose Material 3, on the stable 1.4 line. Expressive components are adopted once 1.5 is stable. |
| Adaptive layout | Material 3 adaptive navigation and list-detail panes |
| Networking | OkHttp for REST and websockets, kotlinx.serialization for parsing (proposed) |
| API client | Generated from the Proxmox API schema. Parsing tolerates 0/1 booleans and numbers sent as strings. |
| Forms | A schema-driven form renderer for long-tail settings; hand-designed screens for main flows |
| Live status | Polling tied to what is on screen. One cluster-resources call feeds the lists. |
| Async actions | One task tracker follows every action to completion and reports the result |
| Background work | WorkManager for alert checks; a foreground service for open console sessions |
| Local storage | Preferences and cached state on device; secrets under Android Keystore keys |
| Native code | LibVNCClient built with the NDK, with 16 KB page size support |
| Android versions | Target API 36, which Google Play requires. Minimum version to be decided. |

- **Modules:** core API, authentication, feature modules per area, terminal, remote desktop, and a shared design system.
- **Version handling:** the app reads the server version at sign-in and enables features by capability, not by assumption.
- **Test lab:** a nested Proxmox cluster per supported version, including Ceph, a Backup Server and each sign-in method.

## Milestones

Nine milestones in build order; the first public release follows milestone 4.

| # | Milestone | Contains | Done when |
| --- | --- | --- | --- |
| 1 | Foundation | Server profiles, all sign-in methods, TLS trust, app lock, overview, guest list and detail, power actions, task tracking | A user can sign in by any method and start or stop a guest |
| 2 | Terminal | Websocket session layer, extra keys, multiple sessions, background keep-alive | A long editing session in a node shell works without workarounds |
| 3 | Remote desktop | Native viewer, both pointer modes, extra-keys bar, hardware input | A Windows and a Linux desktop guest are comfortable to operate by touch |
| 4 | Guest lifecycle | Snapshots, backup and restore, migrate, clone | **First public release on Google Play** |
| 5 | Configuration | Schema-driven forms, hardware editing, create wizards | A VM and a container can be created and fully configured in the app |
| 6 | Nodes, storage and alerts | Node management, storage, backup jobs, alerts, widgets | Routine node maintenance needs no web interface |
| 7 | Datacenter | Firewall, high availability, replication, SDN, Ceph, access control | Feature parity with the web interface for Proxmox VE |
| 8 | Backup Server | Datastores, snapshots, maintenance jobs | A Backup Server can be administered from the app |
| 9 | Datacenter Manager | Remotes, cross-cluster views and migration | A Datacenter Manager can be administered from the app |

Three prototypes belong at the start of milestone 1 because later milestones depend on them: console access with API tokens, WebAuthn in the in-app browser, and VNC over the Proxmox websocket.

## Publishing and licensing

The app ships under the GPL with its source public, under its own name and icon.

**License**

- GPLv3 or later is the recommended version. It is compatible with all three code bases the app builds on.
- [AVNC](https://apt.izzysoft.de/fdroid/index/apk/com.gaurav.avnc?repo=main) is GPL-3.0-or-later, [LibVNCClient](https://github.com/LibVNC/libvncserver) is GPL v2 or later, and the [Termux terminal libraries](https://github.com/termux/termux-app/blob/master/LICENSE.md) are Apache 2.0.
- The full source is published, with third-party license notices kept. The app may still be sold, but anyone may rebuild and redistribute it.
- This is orientation, not legal advice.

**Name and icon**

- The [Proxmox trademark guidance](https://proxmox.com/en/about/company-details/media-kit) does not authorize its marks in app names or domain names. It suggests the form "XY App for Proxmox Virtual Environment".
- The Proxmox logo may not be used as the app's icon.
- The app therefore needs its own name and icon, with "for Proxmox VE" as a descriptor and a note that it is unaffiliated.

**Google Play**

- New apps must [target Android 16 (API level 36)](https://developer.android.com/google/play/requirements/target-sdk) or higher.
- A personal developer account created after November 13, 2023 must run a [closed test](https://support.google.com/googleplay/android-developer/answer/14151465) with at least 12 testers opted in for 14 continuous days before production access. That test can start as soon as milestone 1 is usable.
- The data safety form and privacy policy state that credentials stay on the device and that the app collects nothing.
- Because the app is GPL, F-Droid is an optional second channel.

## Risks and open questions

The first three risks below need a prototype before the plan is firm; seven product questions are still open.

| Risk | Why it matters | Fallback |
| --- | --- | --- |
| Console access with API tokens may be restricted | Token-only users could lose the terminal and remote desktop | Ask for a ticket-based sign-in when a console is opened |
| WebAuthn may not work in the in-app browser | Security-key users could not sign in | Hand off to the system browser and return to the app |
| LibVNCClient expects a direct socket, not a websocket | The remote desktop depends on it | A local bridge inside the app between the websocket and the library |
| Schema-driven forms can feel generic | Parity screens may look unfinished | Hand-design the most used forms first |
| Proxmox APIs change between releases | Features can break on upgrade | Capability checks and the per-version test lab |

Open questions:

- [ ] App name and icon
- [ ] Free, paid, or free with optional support
- [ ] Oldest supported Proxmox VE version (8 and 9 proposed)
- [ ] Oldest supported Android version
- [ ] Alerts by on-device polling only, or also an optional self-hosted relay
- [ ] Personal or organization Google Play account
- [ ] Whether Mail Gateway is ever in scope

## Sources

- [Proxmox downloads: latest releases](https://www.proxmox.com/en/downloads/)
- [Proxmox VE User Management](https://pve.proxmox.com/wiki/User_Management)
- [Proxmox Datacenter Manager: Authentication and Access Control](https://pdm.proxmox.com/docs/access-control.html)
- [Proxmox VE docs patch: VNC clipboard](https://lore.proxmox.com/pve-devel/20231114092256.86137-7-m.frank@proxmox.com/raw)
- [Proxmox media kit and trademark guidance](https://proxmox.com/en/about/company-details/media-kit)
- [Termux app license](https://github.com/termux/termux-app/blob/master/LICENSE.md)
- [AVNC listing](https://apt.izzysoft.de/fdroid/index/apk/com.gaurav.avnc?repo=main)
- [LibVNCServer and LibVNCClient](https://github.com/LibVNC/libvncserver)
- [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [Google Play target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [Google Play testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
