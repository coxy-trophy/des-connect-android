# DES Connect Android interaction ownership

This visual refinement retains upstream KDE Connect behavior. Protocol and trust transitions remain owned by Device, PairingHandler and transport implementations, not the visual theme.

| Capability | Canonical owner | Contract |
|---|---|---|
| Navigation | MainActivity, navigation drawer and Fragment back stack | Preserve existing destinations, back and selected device |
| Device discovery | PairingViewModel / PairingScreen | Real connected/available/remembered data; pull to refresh retains upstream timing |
| Pairing | DeviceFragment / DevicePairingScreen / PairingHandler | Request sends real request; accept and reject invoke existing callbacks; show verification key when pending; errors remain visible |
| Permissions | Existing Fragment permission launchers and plugin explanation dialogs | Retain OS-owned permission semantics and existing recovery callbacks |
| Select/Listbox | Android ListPreference / platform dialogs | Native locale, focus, selection and dismissal behavior |
| Form | Existing preference and plugin activities | Preserve validation and entered values; theme changes only presentation |
| Toast | Existing Snackbar / Toast owners | No added notifications, timers or duplicate status channels |
| Scrollbar | Android scrolling containers | Native overflow and gesture behavior; pairing and discovery each own their scroll |
| Dialogs | MaterialAlertDialogBuilder / DialogFragment | Existing confirmation, focus and back behavior |
| Theme | ThemeUtil, SettingsFragment, resource qualifiers | Default dark for new installations; explicit light/dark/system preserved |

All existing translations remain. New design requires no English-only product strings. Font scaling and wrapping must preserve action access. Discovery rows, pairing buttons and permission actions have minimum 48dp touch geometry. Native interactive cards expose button semantics; explanatory text does not acquire an empty click handler.

No data mutation, permission, authentication, polling or remote-control behavior is changed by the visual system. No destructive lifecycle action is added.

## Peer capability filtering (2026-11-03)

Device initializes supported plugins from the initial identity before loading modules. PluginFactory owns the existing directional OR matching: a shared send or receive packet type is sufficient. Both explicit capability vectors, including empty sets, are authoritative; missing/null vectors retain the upstream legacy fallback. Later explicit updates refresh the derived selection even when DeviceInfo is aliased. The existing plugin lifecycle removes unsupported loaded modules and both permission maps, so views never need a desktop-specific allowlist.

The paired, reachable plugin view shows Connected and a resource-backed explanation when it has no controls or permission requests. Existing overflow actions, including Send ping when PingPlugin is supported, remain available. For peers with explicit capability vectors, controls and permission requests derive from matching modules rather than Android module availability alone.

## File offer approval window

The payload listener waits at most60seconds for a receiving peer to connect,
allowing DES desktop users to review its30-second incoming-file offer. TLS
handshake deadlines are unchanged. This wait neither accepts a file nor changes
Android's existing incoming-file behavior; desktop approval belongs to the native
service/panel. Listener, socket and payload are closed by the existing finally
block on success or failure. Cancellation retains upstream semantics and may wait
until this bounded accept timeout when no receiver connects.

## Reused-link identity changes (2026-11-03)

KdeConnect.connectionListener reloads the device plugin lifecycle when an existing link reports changed identity/capability data, before notifying device-list observers. Loaded handlers, menu actions and permission rows must match the updated supported set without requiring a new link or application restart. Identical identity data does not trigger a reload or observer notification. DeviceCapabilitiesTest exercises the actual callback with real plugin load/unload and observer snapshots.

## Plugin startup replies (2026-11-04)

Device serializes plugin packet dispatch with plugin reload on the same monitor. Replies to requests sent during plugin creation wait until creation and incoming-interface registration finish. Reload cannot destroy a plugin while its packet handler is running, and packets arriving during unload cannot reach the removed plugin. DeviceCapabilitiesTest exercises these interleavings with real Device reload/dispatch and controlled Plugin lifecycle hooks; no request retries or timing sleeps are added.

## Independent system-volume controls (2026-11-04)

A loaded SystemVolumePlugin exposes its translated control button when MprisPlugin
is absent. It opens the existing Multimedia control activity directly on Devices.
When both plugins are loaded, the existing combined entrypoint owns navigation
and the volume plugin adds no duplicate button. The activity omits MPRIS settings
when that plugin is unavailable. Permission policy remains desktop-owned; this
entrypoint does not enable media export or change any volume by itself.

The multimedia pager includes only pages whose plugins are loaded: Play for
MPRIS, Devices for system volume, both when available, and immediate activity
closure when neither remains at launch. This prevents adjacent-page preloading
from creating a missing MPRIS fragment that would close volume-only controls.
SystemVolumeUiTest verifies the actual adapter's fragment classes for all four
permission combinations as well as the button's target device/tab intent.

Pager identities remain Play=0 and Devices=1 across capability combinations.
Unavailable page IDs are excluded from restoration, preventing a saved Play
fragment from being restored into a volume-only slot. Hardware volume-key routing
looks up fragments by stable item ID and ignores invalid positions. The adapter
regression verifies retained IDs, exclusion of unavailable IDs and actual
volume-key callback delivery through the framework's fragment map.

## Phone as a key (2026-11-12)

PhoneKeyPlugin implements `des.phonekey.v1` as specified in the DesOS tree
(`desos/phonekey/README.md`, sections 3 and 4). It is disabled by default for
every device. Its packet type is absent from the broadcast identity and from
identities sent to unpaired devices; DeviceHelper adds it only to the encrypted
identity for a paired device on which the user enabled the plugin. Toggling it
closes that device's link and refreshes connections, because capabilities are
exchanged only when a link starts.

| Capability | Canonical owner | Contract |
|---|---|---|
| Per-desktop switches | PhoneKeySettingsFragment, per-device preferences | Walk-away lock, unlock requests and administrator approvals are separate switches, all off by default. No switch remembers an approval |
| Key | PhoneKeyStore (Android Keystore) | One non-exportable P-256 key per desktop; every use needs a Class 3 biometric; invalidated by new biometric enrolment; deleted on revoke, unpair, invalidation or user request |
| Request prompt | PhoneKeyPlugin notification, then PhoneKeyActivity | One request on screen at a time. Desktop name, purpose, user and action are shown exactly as validated; the notification has no approve action |
| Approval | Android BiometricPrompt with the signature as CryptoObject | The signed message is built from the displayed request object. Cancelling or failing the biometric answers denied |
| Expiry | PhoneKeyPlugin timer, desktop `cancel` | Notification, prompt and biometric sheet are dismissed and nothing is sent |
| Presence | PhoneKeyPlugin packet handler | Answered from the background service without any UI, only while the walk-away switch is on |
| Enrolment | Desktop only | The phone has no control that starts enrolment. The new key's fingerprint is shown before approval and stays on the plugin's settings page |

Validation, the signed-message encoder and the one-request slot are in
PhoneKeyProtocol, which has no Android dependency and is tested on the JVM
against the desktop's vectors. Strings are English resources in
`values/strings.xml`; no translation was added or changed.
