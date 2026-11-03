---
version: alpha
colors:
  ground: '#06080B'
  panel: '#0A0E12'
  track: '#141B22'
  line: '#1A232B'
  text: '#DCE7EC'
  secondary: '#8FA3B0'
  primary: '#4FE3D8'
typography:
  display:
    fontFamily: 'Chakra Petch'
  utility:
    fontFamily: 'JetBrains Mono'
rounded:
  control: '6px'
  surface: '10px'
omitted:
  - section: spacing
    reason: 'Native Android dp geometry: 8dp rhythm, 16–24dp margins, minimum 48dp touch targets.'
---
## Overview
DES Connect is the Android companion to the DES desktop. Native task controls use the desktop's precise matte instrument language, scaled for touch. The signature is the shared Signal cyan with Chakra Petch interface text and a JetBrains Mono pairing fingerprint. Preserve the GPL upstream functionality, credits, locale resources and Android permission flows.

## Colors
The normative Android runtime palette lives in `src/main/res/values/colors.xml` and `values-night/colors.xml`. The dark palette adapts sibling `des-ui/shell/src/ui.rs` and `des-ui/themes/signal.json`. XML Material attributes in `values/styles.xml` and Compose `ui/compose/KdeTheme.kt` both consume those resources; never duplicate colors in screen code. New installations default to dark Signal. Existing explicit Light, Dark and System preferences remain functional. The light variant preserves Signal hue and readable contrast. Wallpaper-driven DynamicColors is intentionally removed to keep desktop identity.

Use bright text for names and actions, secondary for descriptions, cyan for focus/selection and device glyphs, error for incompatible protocol warnings. Borders distinguish surfaces; controls use the higher-contrast outline role. No glow, gradient, blur or animated decoration.

## Typography
Chakra Petch is the shared display and interface face. Keep Android Material's accessible sp type scale rather than copying the desktop's 13px baseline. JetBrains Mono identifies the pairing verification value. Bundled static fonts load locally without network requests; their OFL notices are retained in LICENSES. Android's fallback handles unsupported scripts. Long device/plugin names and security values wrap.

## Layout
Device discovery is one scrollable list with connected, available and remembered sections. Device rows use a 32dp real type glyph, readable name and optional compatibility message. Use 16dp row padding with a minimum 72dp row height. Main discovery, pairing and plugin content centers within 720dp on wide displays. Pairing is vertically scrollable for landscape and large text; incoming accept/reject actions stack to avoid localized label collisions. Existing navigation drawer and app bar remain canonical.

## Elevation & Depth
Opaque ground, panel and track surfaces; zero card elevation, fine neutral borders. The app bar shares the ground instead of a tinted floating slab. Platform popups and modal dialogs retain native semantics and use shared theme attributes.

## Shapes
6dp control radius; 10dp surface radius, mirrored in XML shape appearances and Compose Shapes. Touch actions retain at least 48dp height; never copy desktop control height. Existing specialized plugin geometry remains where functionally meaningful.

## Components
`KdeTheme` adapts Android resources to Material3 Compose colors, typography and shapes. `KdeConnectThemeBase` adapts the same resources to AppCompat/View screens and dialogs. `KdeCard` owns clickable outlined list cards and ripple semantics. `Des.Button` owns XML button height and radius; `MainNavigationView` uses the same control shape. Both `alertDialogTheme` and `materialAlertDialogTheme` adapt floating surfaces through `KdeConnectDialogStyle` and both framework/AppCompat `colorBackgroundFloating` attributes. Material buttons keep platform focus, pressed, disabled and accessibility behavior. `ThemeUtil` and Settings own theme preference changes.

## Do's and Don'ts
Preserve discovery, refresh, permission handling, trust transitions, pairing timeout and plugin actions. Never invent connectivity/status values or add inactive future controls. Add no idle polling, decorative animation or dependencies. Existing progress indicators communicate actual work and platform motion preferences remain authoritative. Native tablet/phone screenshot acceptance accompanies build checks; do not claim visual acceptance from compilation alone.
