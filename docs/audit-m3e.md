# Material 3 Expressive audit — MaterialAgent

Static audit of `app/src/main/java/com/materialagent`. Read-only; findings are appended
dimension by dimension. Format: `file:line` — defect — fix.

Scope note: findings under `res/mipmap/**`, the brand-mark composables and the six
brand-mark call sites in `ui/screens/**` may be superseded by concurrent work by other
agents and are flagged as such where they appear. The tree was being edited *while* this
audit ran (the `AgentOrb` → `AgentArt` rename landed mid-pass), so treat line numbers as
approximate and confirm against the symbol names, which are stable.

Method: static reading of `app/src/main/java/com/materialagent`, plus `javap` against
`material3-1.5.0-alpha15/material3.aar` in the Gradle cache wherever a claim depended on
which overload the compiler would pick (the shapes overloads, and the floating toolbar's
container height and corner token). No screenshots; no build was needed, since none of the
findings depend on runtime behaviour.

---

## 1. Shapes

### 1.1 Non-morphing buttons (memory #2385)

Verified against the actual bytecode: in material3 1.5.0-alpha15 both `TextButton` and
`IconButton` have a second overload taking `ButtonShapes`/`IconButtonShapes` (`javap` on
`ButtonKt`/`IconButtonKt` in `material3.aar`), so every one of the sites below *can* take
the morphing overload. A button drawn through the older overload keeps a static outline
and only changes colour on press.

- `ui/screens/sessions/SessionsScreen.kt:501` — conversation-actions `IconButton` passes
  no `shapes`; static circle, no press morph. Fix: add `shapes = IconButtonDefaults.shapes()`.
- `ui/components/media/ImageAttachment.kt:255` — fullscreen-image dismiss `IconButton`,
  same defect. Fix: same.
- `ui/theme` consumers of `TextButton` with no `shapes` — no press morph on any of them
  (radius is unaffected, since the default is already the pill):
  `ui/components/Surfaces.kt:83,85,119,240`; `ui/components/UpdateBanner.kt:193`;
  `ui/components/media/ImageAttachment.kt:160`; `ui/screens/chat/ChatEntries.kt:295,788,806`;
  `ui/screens/sessions/SessionsScreen.kt:313,320,379,385,633,638`;
  `ui/screens/settings/SettingsScreen.kt:729`. Fix: `shapes = ButtonDefaults.shapes()`
  (`@OptIn(ExperimentalMaterial3ExpressiveApi::class)` at each site).

Note the asymmetry this creates: the *primary* buttons and the destructive-confirm
buttons already morph (`ChatEntries.kt:786,820,843,900`, `ConnectScreen.kt:363`, …), so in
an approval row the prominent choice animates on touch and the `Cancel` beside it does not.

`Surface(onClick = …)` sites (`NavBar.kt:161`, `SessionsScreen.kt:435`) cannot take a
`shapes` overload at all — those are fine and are not counted above.

### 1.2 Nesting: a tray rounder than the card holding it

- `ui/screens/settings/SettingsScreen.kt:513` (`SettingsGroup`, `RoundedCornerShape(26.dp)`)
  contains, through `SettingsRow`'s 16dp padding, the `ExpressiveToggleGroup` at
  `:131,164,184,202`. That tray's radius is `AgentShapes.toggleTray` = `48/2 + 4` = **28dp**
  (`ui/theme/Shape.kt:88`), i.e. the *inner* surface is rounder than the card around it
  while the ring is 16dp. A 16dp ring with a 26dp outer corner wants ≈10dp inner, so the
  tray bulges at the corners and reads thin along the edges — the same failure the
  `ExpressiveToggleGroup` docs describe for its own ring, one level up. Fix: give the
  group a flattened tray when nested (`toggleTray` is right for a free-standing tray, not
  for one 16dp inside a 26dp card), or drop the card radius to a value derived from
  `toggleTray + inset`.

### 1.3 Radii that exist once and match nothing

- `ui/screens/settings/SettingsScreen.kt:450` and `:515` — `RoundedCornerShape(26.dp)`
  written twice: the connection card and every settings group. 26dp is not a step of the
  scale (22 `large`, 32 `extraLarge`) and no other surface in the app uses it.
- `ui/screens/sessions/SessionsScreen.kt:439` — `RoundedCornerShape(24.dp)` for a session
  card. Two list screens' near-identical container cards now differ by 2dp (26 vs 24)
  against a scale that offers 22 or 32; that reads as an accident, not a decision.
- `ui/screens/sessions/SessionsScreen.kt:628` — `RoundedCornerShape(14.dp)` for the rename
  dialog's `OutlinedTextField`. The dialog it sits in renders at `MaterialTheme.shapes.extraLarge`
  (32dp; the app's `extraLarge` override), and every other text field is `medium` (16dp)
  or `AgentShapes.pill`. Three values for one control.
- Fix for all four: read `MaterialTheme.shapes.large` / `.medium`, or add the value to
  `AgentShapes` with a reason (as `composerIdle`/`composerActive` already do) so it has one
  home.

### 1.4 Checked and found consistent (not defects)

- Floating nav bar: `HorizontalFloatingToolbar` is 64dp tall with `CornerFull`
  (`FloatingToolbarTokens`, read from bytecode), i.e. a 32dp outer radius. The
  `NavigationPill` inside it (`NavBar.kt:168`, `AgentShapes.pill`) is 22dp icon + 10dp×2
  padding = 42dp → radius 21, inset (64−42)/2 = 11, so concentric would need 21 + 11 = 32.
  It is concentric.
- `ExpressiveToggleGroup`'s own tray/item geometry is derived from `toggleItemHeight`
  rather than guessed (`ui/theme/Shape.kt:78-88`), and `:120` clips with the same token it
  documents. No defect.
- `ModalBottomSheet` (`SessionInfoSheet.kt:240`) overrides no shape, so it takes the
  theme's `extraLarge` (32dp) — consistent with dialogs.

---

## 2. Motion

The codebase is in unusually good shape here: every `animate*AsState`, `AnimatedVisibility`,
`animateContentSize` and `animateItem` in `ui/` reads one of the seven composable helpers,
and the only two raw `tween`/`spring` sites left are ambient loops. Findings are the
exceptions.

### 2.1 Animations that ignore reduced motion

- `ui/screens/settings/SettingsScreen.kt:439-445` and
  `ui/screens/sessions/SessionsScreen.kt:425-432` — use `ExpressiveMotion.Specs.alpha` /
  `.color`, which are non-composable constants built from `MotionScheme.expressive()`
  (`ui/theme/Motion.kt:113-121`). They cannot follow the reduced-motion branch, so these
  two screens still animate with expressive springs when the user has asked for calmer
  transitions, while every other screen gets `standard()`. Fix: `alphaSpec()` / `colorSpec()`
  at those five call sites, then delete `ExpressiveMotion.Specs`.
- `ui/screens/chat/ChatEntries.kt:321-325` — the streaming caret's `infiniteRepeatable(tween(700),`
  `RepeatMode.Reverse)` is a raw tween with no reduced-motion guard. Compare
  `ui/components/Expressive.kt:89,110,208` (`AgentArt` breath/sheen, `LivePulse`), which are
  the same kind of ambient loop and *are* correctly gated on
  `LocalMotionLevel.current == MotionLevel.REDUCED`. A caret that blinks forever is exactly
  the persistence a reduced-motion user is asking to be rid of. Fix: reuse the same guard.
- Stale premise worth noting: `Motion.kt:104-108` says `SessionsScreen`/`SettingsScreen`
  "cannot be edited here" because "another agent owns" them. Nothing else in the tree is
  written that way, and the guard exists — the comment is now misleading rather than true.

### 2.2 Accepted fixed durations (not defects)

- `Expressive.kt:90-96` (1800ms breath), `:107-111` (2800ms sweep), `:203-219` (1000ms
  live pulse): ambient moods with no `MotionScheme` duration of their own, each documented
  and guarded on reduced motion.
- `ui/components/UpdateBanner.kt:255` — `ProgressIndicatorDefaults.ProgressAnimationSpec`;
  a library token for a determinate progress ramp, not a hand-rolled spring.
- `ui/components/ScrollHaptics.kt` — has no animation at all; see 3.2.

---

## 3. Typography and layout

### 3.1 Type scale — correct

Every screen title is `displaySmall` (`SettingsScreen.kt:107`, `CapabilitiesScreen.kt:122`,
`SessionsScreen.kt:339`, `ConnectScreen.kt:200`), section headings are `titleSmall` via the
shared `SectionHeader` (`Surfaces.kt:142`, 13 call sites), and selected-item emphasis uses
M3E's `labelLargeEmphasized` (`NavBar.kt:222`, `ExpressiveToggleGroup.kt:87`). No screen
falls back to a bold `bodyLarge` as a title. No finding.

### 3.2 Content column

- `SettingsScreen.kt:101`, `CapabilitiesScreen.kt:114`, `SessionsScreen.kt:156` — all four
  list screens use the same `PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
  bottom = 120.dp)` column, matching `SectionHeader`'s deliberate lack of horizontal
  padding. Consistent; `bottom = 120.dp` is the floating nav bar clearance (memory #2367).
- `ChatScreen.kt:386,409` — the transcript uses `top = 12.dp, bottom = 20.dp` and gets its
  horizontal inset from the bubbles instead. Intentional for a chat list; not counted as a
  break in the column.
- `ModelPicker.kt:237` — `horizontal = 12.dp`, a popover rather than a screen column.

### 3.3 Touch targets

Covered under 7.2, since that is where the 48dp rule is load-bearing.

---

## 4. Components

### 4.1 One segmented control, everywhere — verified

`ExpressiveToggleGroup` is the only enumerated-choice control in the app: grepping the whole
`ui/` tree finds no `SegmentedButton`, no `FilterChip`, and no raw `ToggleButton` outside
`ExpressiveToggleGroup.kt:213,222` itself. It is used at 8 sites across 5 screens
(`SettingsScreen.kt:131,164,184,202`, `CapabilitiesScreen.kt:151`, `ModelPicker.kt:532`,
`SessionsScreen.kt:181`, `ConnectScreen.kt:279`), all of which get the connected
`ButtonGroupDefaults` shapes and `ConnectedSpaceBetween` from the one implementation
(memory #2337 satisfied). `Switch` is used only for real booleans. No finding.

### 4.2 Tray radii

All trays are clipped with the single `AgentShapes.toggleTray` token
(`ExpressiveToggleGroup.kt:120`), so no two trays can drift. The only radius defect in the
whole tray family is the *nesting* case in 1.2.

### 4.3 Dead composable

- `ui/components/Surfaces.kt:280` — `SoftVisibility` has zero call sites in the app tree
  (only its own definition matches). Every fade/expand in the app now passes its spec
  explicitly. Fix: delete it, or the next reader will assume it is the sanctioned way to
  fade something and reintroduce a second pattern.
- Not counted: `AgentOrb` vs `AgentArt` currently reads as a rename in flight
  (`Surfaces.kt:209`, `Expressive.kt:75` and the 6 call sites), which is the concurrent
  brand-mark work, not a defect.

## 5. Haptics

### 5.1 Three distinct meanings share one waveform

- `ui/haptics/Haptics.kt:63` — `HapticCue.SENT, HapticCue.TOOL_DONE, HapticCue.INTERRUPTED ->`
  `effect(s, listOf(0L))`: the same single tick at the same amplitude for "I heard your
  message", "a tool the agent ran has finished" and "the turn was stopped". The point of a
  named vocabulary is that the pocket can tell these apart, and `SENT` is the most-used cue
  in the app (every submit, every nav tap, every segment). Fix: give `INTERRUPTED` at least
  a distinct shape (it is a deliberate stop) or an amplitude step below `SENT`.

### 5.2 Wrong cue at the call site

- `ui/components/NavBar.kt:98` — switching destinations fires `HapticCue.SENT`
  ("heard you"; the same cue the composer uses to send a prompt). Navigation is precisely
  the custom `UI_ACTION` cue ("a deliberate tap on a control that opens something"), which
  the neighbouring call sites use. Fix: `UI_ACTION`.
- `ui/screens/settings/SettingsScreen.kt:135,170,188` — the Theme, Colour and Motion
  preference trays fire `SENT`, while the fourth identical tray (Haptics, `:208`) fires
  `TOGGLE`. Same control four times, two different meanings. Fix: `TOGGLE` at all four.
- `ui/screens/capabilities/CapabilitiesScreen.kt:141` — the refresh icon button fires
  `SENT`; `SessionsScreen.kt:147` fires `REFRESH` for the same gesture. Fix: `REFRESH`.
- `ui/screens/capabilities/CapabilitiesScreen.kt:155` — the tab tray fires `SENT` where
  the identical session-filter tray (`SessionsScreen.kt:185`) fires `TOGGLE`. Fix: `TOGGLE`.
- `ui/screens/capabilities/CapabilitiesScreen.kt:233` — enabling a toolset fires
  `TOOL_DONE`, i.e. flipping a switch is announced as "a tool the agent ran finished".
  Fix: `TOGGLE`.
- `ui/screens/chat/ChatEntries.kt:769,780` — answering a sudo secret fires
  `NEEDS_ATTENTION` ("come back, you are needed"), so the acknowledgement of the answer
  uses the cue that summoned the user. Fix: `SENT` (which is what the Cancel path at
  `:808` and every other interactive answer already use).
- `ui/screens/connect/ConnectScreen.kt:138,144` — connection success/failure fire
  `TURN_COMPLETE` / `TURN_FAILED`, whose docstrings are about a *turn* ("completion should
  feel like a full stop"; "a heavy low thud, unmistakably not-good"). With both cues
  overloaded, "your answer finished" and "the server is unreachable" are the same buzz.
  Fix: either add `CONNECTED`/`CONNECT_FAILED` cues or accept and document the overload.

### 5.3 Streaming and scroll haptics (memory #2383) — verified correct

- `ui/components/ScrollHaptics.kt:63-84` listens on `onPreScroll` only, so programmatic
  auto-scroll (which does not travel through the nested-scroll chain) never ticks. The
  `ScrollTickGate` rate cap (`:36-45`) is also the right call.
- `ui/screens/chat/ChatScreen.kt:212` gates `STREAM_TICK` on the user's `streamingHaptics`
  preference before it reaches the vibrator, and `Haptics.kt:49-58` caches the effects so a
  tick every ~18ms allocates nothing. Correct.

---

---

## 6. Colour

Dynamic colour and the `expressive()`→`standard()` motion-scheme split were verified in the
prior pass and are not re-examined here. Two contrast risks remain, both in the server-skin
path rather than the brand scheme.

- `ui/theme/Color.kt:206-212, 219-225` (`skinScheme`) — `onPrimaryContainer` is set to the
  *raw* server accent while `primaryContainer` is that accent at 24–28% alpha composited
  over the surface. A light accent (the `ui_accent`/`banner_accent` keys accept any hex;
  a pale gold like `#FFDF9E` is plausible) composites to a near-white container and then
  paints the pale accent on it — roughly 1.2:1, i.e. text that is not there. Fix: derive
  `onPrimaryContainer` from `readableOn(container)` *after* compositing, or leave the
  content role on the theme's own value.
- `ui/theme/Color.kt:247-250` (`readableOn`) — uses the luma approximation
  `0.299r + 0.587g + 0.114b` with a 0.6 threshold. That is not WCAG relative luminance and
  it is not gamma-aware, so mid-tones take white text: `#7A7A7A` (luma 0.48) gets white at
  about 4.0:1, under the 4.5:1 bar for body text. Fix: compute WCAG relative luminance for
  both black and white and take the higher ratio.
- `ui/theme/Color.kt:29-107, 112-190` — the brand scheme itself is sound: real M3 tonal
  ramps (40/90/10 light, 80/30/20 dark), the `*Fixed` roles explicitly overridden in *both*
  schemes so they cannot fall back to baseline purple, and no content role repurposed for
  decoration. No finding.

---

## 7. Accessibility

### 7.1 Icon-only controls — verified correct

- `ChatScreen.kt:874-880` — the send `FilledIconButton` carries
  `contentDescription = "Send message"` on its **child `Icon`**, not on the parent. The
  earlier audit's alarm on this control was reading the wrong node; there is no defect.
- `ChatScreen.kt:934-945` — the attach `IconButton` reads "Attach a photo, sound or file".
- `ChatScreen.kt:610,669` (back, overflow), `ChatScreen.kt:464`, `SessionsScreen.kt:405`
  (clear search), `ModelPicker.kt:506`, `PullRevealPanel.kt:112`, `SessionInfoSheet.kt:560`,
  `UpdateBanner.kt:155,224`, `CapabilitiesScreen.kt:144` — all label their child icon.
- Every remaining `contentDescription = null` in the tree belongs to a decorative leading
  icon beside a text label or an item in a labelled menu (checked node by node: e.g.
  `ChatScreen.kt:943,951,959`, `SessionsScreen.kt:507,515,523`, `ConnectScreen.kt:252-345`).
  Suppressing those is correct.

### 7.2 Touch targets under 48dp

The app demonstrably knows the rule — `CapabilitiesScreen.kt:454`, `ModelPicker.kt:450,558`,
`ChatEntries.kt:373`, `ChatScreen.kt:889` and `Surfaces.kt:236` all reserve
`heightIn(min = 48.dp)`, and the composer's send/attach buttons pin exactly 48dp. These five
sites break it:

- `ui/components/media/OutgoingChips.kt:178` — remove-attachment `FilledTonalIconButton` at
  `size(28.dp)`: a 28dp touch target on the control that deletes the user's attachment.
  Fix: keep the 28dp visual and put it in a 48dp hit area, or `minimumInteractiveComponentSize()`.
- `ui/components/media/OutgoingChips.kt:245` — same control in the file-chip variant, 32dp.
- `ui/components/media/AttachmentDownloadButton.kt:43` — `size(44.dp)` overrides the
  `IconButton` default's own 48dp reservation, so the save action is 44dp.
- `ui/components/media/AudioAttachment.kt:363` — play/pause at `size(44.dp)`, the primary
  action of an audio row.
- `ui/components/NavBar.kt:161-175` — the `NavigationPill` is 22dp icon + 10dp×2 padding =
  **42dp tall** with no minimum, even though the toolbar around it is 64dp and has room.
  This is the app's primary navigation; it is the only interactive control in the tree with
  a sub-48dp target on *both* axes for every tap.

### 7.3 Selection state not announced

- `ui/components/NavBar.kt:161` — the destination pill is a `Surface(onClick = …)`, which
  publishes a button role and no selection. The current destination is conveyed only by
  colour and by the appearing label, so a screen reader user gets no "selected" — on a
  control whose whole documented purpose is "the shape of the bar communicates where you
  are". Fix: `Modifier.semantics { selected = isSelected; role = Role.Tab }` (or
  `selectable(selected = …)`), which is what the equivalent `ExpressiveToggleGroup` item
  already does with `Role.RadioButton` (`ExpressiveToggleGroup.kt:206`).

### 7.4 Large-font scaling

- `ui/components/ExpressiveToggleGroup.kt:203` — the item is pinned with `.height(...)`, not
  `.heightIn(min = ...)`: `AgentShapes.toggleItemHeight` is 48dp *always*. The group's
  overflow protection (`splitWouldClip`, `:152-180`) measures label **width** only, so at a
  large font scale a segment label that fits the width is still clipped vertically by the
  fixed height — the one scaling hazard the group's own comments do not cover. Fix: either
  `heightIn(min = …)` plus a tray radius derived from the measured height, or scale
  `toggleItemHeight` by `LocalDensity.current.fontScale`.
- Correct elsewhere: `NavBar.kt:139` drops pill labels above `fontScale > 1.25` and keeps the
  `contentDescription`; `ExpressiveToggleGroup` scrolls rather than truncates; no screen
  fixes a text container height. Note `NavBar.kt:139`'s threshold is a hard-coded 1.25
  rather than a token — acceptable, but it is the kind of number that should have one home.

---

## Prioritised summary

Ranked by (user-visible harm) × (cheapness of the fix). Everything here is a small, local
change; nothing requires rework of the theme.

**Fix first — visible on every screen, or on the most-used controls**

1. **Non-morphing buttons** (1.1). Two `IconButton`s and sixteen `TextButton`s still use
   the single-shape overload, so they do not morph on press while the buttons beside them
   do. Mechanical: add `shapes = …Defaults.shapes()`.
2. **The tray rounder than its card** (1.2). `ExpressiveToggleGroup`'s 28dp tray sits 16dp
   inside `SettingsGroup`'s 26dp card, four times on the Settings screen. This is the
   visual defect the owner described, and the fix is one derived value.
3. **Navigation pill: 42dp touch target and no announced selection** (7.2, 7.3). Primary
   navigation, both problems in one composable.
4. **Sub-48dp touch targets on the attachment controls** (7.2): 28dp and 32dp delete
   buttons, 44dp play/pause and save. These are the controls users reach for first with an
   attachment in front of them.

**Fix next — correctness of the design language**

5. **Haptics: three cues, one waveform** (5.1), plus the wrong-cue call sites (5.2) —
   `SENT` on navigation and on all three preference trays, `NEEDS_ATTENTION` as an answer
   acknowledgement, `SENT` where `REFRESH`/`TOGGLE` is used elsewhere for the same gesture.
   The vocabulary already has the right cue in every one of these cases.
6. **Reduced motion leaks in two screens and one caret** (2.1): `ExpressiveMotion.Specs` in
   `SettingsScreen`/`SessionsScreen` and the raw `tween(700)` caret. The guard already
   exists elsewhere in the file — this is deletion of a stale workaround.
7. **Large-font clipping in the toggle group** (7.4) — the item height is a hard 48dp while
   the overflow protection only measures width.

**Fix when touching the area**

8. **Four one-off radii** (1.3): 24dp and 26dp cards, a 14dp field inside a 32dp dialog.
9. **Server-skin contrast** (6): `onPrimaryContainer = accent` can render ~1.2:1 on a light
   accent, and `readableOn` is not gamma-aware. Only reachable with a server skin.
10. **Delete `SoftVisibility`** (4.3) — zero call sites.

**Verified sound, so no work needed** — the type scale and the 16dp content column (3.1,
3.2); one shared `ExpressiveToggleGroup` and no other segmented control (4.1); the
`NavigationPill`/floating-toolbar radius derivation, which is genuinely concentric (1.4);
scroll and streaming haptics (5.3); the brand colour scheme including the fixed roles (6);
every icon-only control's `contentDescription`, including the send button the earlier audit
false-alarmed on (7.1).
