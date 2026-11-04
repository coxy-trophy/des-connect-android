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
