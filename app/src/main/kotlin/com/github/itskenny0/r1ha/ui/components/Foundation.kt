package com.github.itskenny0.r1ha.ui.components

/**
 * R1 design-system foundation: index + adoption note.
 *
 * This file documents the shared layout layer every screen migrates onto. It declares no
 * code; the canonical pieces live next to it:
 *  - tokens:  [com.github.itskenny0.r1ha.core.theme.R1] (spacing scale `R1.space.*`, type
 *             ramp, palette, shapes, `R1.MinTarget`)
 *  - chrome:  [R1TopBar]   single screen header (chevron + title + trailing action + rule)
 *  - group:   [R1Section]  the one way to title a group of rows
 *  - row:     [R1Row]      the one list / settings row (48dp target, primary/secondary text)
 *  - chip:    [R1Chip]     the one chip (Filter / Action / Pill variants)
 *  - button:  [R1Button]   primary / secondary action button (pre-existing, unchanged)
 *
 * ── How to adopt the foundation (for the per-screen sweep) ──
 *
 * Spacing: replace every literal dp with the nearest `R1.space` step. Mapping used while
 * building the foundation:
 *   2dp  -> R1.space.xxs    4dp  -> R1.space.xs     6dp  -> R1.space.s (round up)
 *   8dp  -> R1.space.s      10dp -> R1.space.m (round up)   12dp -> R1.space.m
 *   14dp -> R1.space.l (round up)   16dp -> R1.space.l
 *   22dp -> R1.space.xl     24dp -> R1.space.xl     32dp -> R1.space.xxl
 * The old 22dp settings gutter becomes 24dp (R1.space.xl). LazyColumn contentPadding stays
 * horizontal = R1.space.m, vertical = R1.space.s; item spacing = R1.space.xs.
 *
 * Components: swap the hand-rolled patterns for the canonical ones.
 *   - Section header (Settings `Section`, Devices `SectionHeader`, Modified-settings
 *     category label) -> [R1Section] with `title`, optional `count`, optional `description`.
 *   - A settings / list row (`GroupCard`, `DeviceRow`, `ModifiedSettingRow`, plain
 *     label+value rows) -> [R1Row] with `label`, `description`, `value`, `onClick`,
 *     `showChevron`, `boxed = true` for the muted-surface card look.
 *   - A toggle/filter chip (Devices `GroupChip`, Logbook `WindowChips`) -> [R1Chip] with
 *     `variant = R1ChipVariant.Filter`, `selected = ...`, `onClick = ...`.
 *   - A tap action chip (top-bar REFRESH / TAIL / DISMISS ALL) -> [R1Chip] with
 *     `variant = R1ChipVariant.Action` (pass `selected` + `tone` for an on-state like TAIL).
 *   - A status / count pill (Devices `MicroChip` DISABLED, modified-count badge) -> [R1Chip]
 *     with `variant = R1ChipVariant.Pill`, `tone = <status colour>`, `onClick = null`.
 *
 * Text hierarchy: primary row text is [com.github.itskenny0.r1ha.core.theme.R1.bodyEmph] in
 * `Ink`; secondary is `labelMicro`/`body` in `InkSoft`; the smallest captions are `InkMuted`.
 * [R1Row] and [R1Section] already encode this, so a converted screen should stop hand-setting
 * those colours per Text.
 *
 * Worked examples that already follow this: ModifiedSettingsScreen (rows + sections) and
 * DevicesScreen (browser: top-bar action, filter chips, status pill, sectioned rows).
 *
 * Out of scope for the foundation (owned elsewhere): card value-bar / tape-meter rendering,
 * the per-theme card bodies, the preference schema/codec, and security/TLS.
 */
private const val FOUNDATION_DOC = "see KDoc above"
