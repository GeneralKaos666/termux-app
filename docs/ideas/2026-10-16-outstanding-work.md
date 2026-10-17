# Outstanding work — the not-yet-done ledger (NOT a plan)

**Date:** 2026-10-16
**Status:** capture only. Nothing here is approved, scheduled, or designed. Each entry records what is
known and what the next step would cost. Items marked *needs spec* require the full cycle
(brainstorming → spec → plan) before any code.
**Origin:** user instruction — "remove what's up next in readme and add what we havent done in
docs/superpowers/ideas". The public README now carries a compact version of this list as a table; this
file is the detailed record, with the grounding for each item, plus the gaps found while building the
sessions reorder and the appearance screen.
**Path classification:** mixed — see the legend. This file is not a spec and authorizes nothing.

**Related:** `specs/2026-10-10-shell-and-sessions-ux-design.md` (§7 milestones),
`plans/2026-10-10-shell-sessions.md`, `plans/2026-10-14-in-app-terminal-appearance.md`,
`ideas/2026-10-14-terminal-engine-upgrade-idea.md`,
`ideas/2026-10-16-termux-float-port-idea.md`.

**Legend:** `[bounded]` small and self-contained · `[needs spec]` architectural, needs its own cycle ·
`[deferred]` postponed on purpose · `[check]` built but not verified on device ·
`[limitation]` accepted as-is for now · `[non-goal]` decided against.

---

## 1. Sessions — pinning and durability

Moved here verbatim in spirit from the README's former "What's next" section.

- **Tab pinning** — `[bounded]` tabs cannot be pinned yet. The bar reorders by hand now; pinning is
  the easy half and needs an ordering operation on the service session list.
- **Session durability / restore** — `[needs spec]` a running shell cannot outlive the app process.
  Sessions live in `TermuxShellManager.mTermuxSessions` in memory and the PTY is in-process, so
  `TermuxService.onStartCommand` returning `START_NOT_STICKY` means process death ends them. Realistic
  "durability" = persist a session snapshot (name/title, command, cwd where capturable) and **respawn**
  fresh sessions on the next launch, opt-in. Write it up separately before building it.
  (Grounding: appearance plan, Appendix — Direction B.)
- **Ordering advice already recorded** — the appendix recommended "appearance first, then
  B-ergonomics, then B-restore". Appearance and drag-to-reorder have both since shipped, so what
  remains of B-ergonomics is pinning (rename/close already exist).

## 2. Sessions bar — two defects found and deliberately left unfixed

Both were reported when the tap/reorder bug was fixed and consciously left out of that commit's scope.

- **Positional fallback tab titles** — `[done 2026-10-16, commit 1d973de4]` the fallback number is now derived
  from the session's stable id, so reordering a session with no name/terminal title keeps its label. The
  dead `number` parameter was removed from `sessionItem(...)` and the `i + 1` argument dropped at
  `TermuxActivity.java:769`; covered by `SessionItemMappingTest`.
- **Only the active tab exposes the ⋮ menu** — `[done 2026-10-16, commit e9434cf7]` every tab now shows
  its own overflow, so rename / close / move are reachable without switching first. The long-press
  divergence noted below was resolved by keeping long-press as the drag handle and the ⋮ as the menu
  entry point on every tab.

## 3. Shell host — A3 Step 2 never done

- `[deferred]` `plans/2026-10-10-shell-sessions.md:133` — "Step 2 — deferred to A3/A4. Route activity
  lifecycle and window-insets through the Compose host; apply IME inset to the terminal content only."
  Still open: the XML shell (`DrawerLayout`, `ViewPager` extra keys) is retained and the Compose host is
  focus-disabled, so lifecycle and insets are not routed through Compose and the IME inset is not
  applied to terminal content only. Deliberately deferred so only one variable changed at a time.

## 4. Device checks not yet run

- **B1 onboarding** — `[check]` `plans/2026-10-10-shell-sessions.md:440` records: "Not exercised:
  cancel/retry after failure, rotation mid-install, back key (left for a later pass)."
- **Appearance presets** — `[check]` the device acceptance run exercised four presets (Termux Default,
  Nord, Dracula, Solarized Dark). `ColorSchemes.kt` now ships seven, so **Solarized Light, Gruvbox Dark
  and One Dark have not been individually checked on device**. A short manual checklist now lives at
  `docs/ideas/ideas.md` (tracked).

## 5. Appearance — deferred minors from review

- `[done 2026-10-16, commit 6c606187]` Dead string resources removed: `title_create_named_session`,
  `action_create_named_session_confirm` (orphaned by the sessions-bar commit), `action_paste` (orphaned by
  the Compose extra-keys bar), and `error_styling_not_installed` + `action_styling_install` (orphaned by the
  appearance-screen commit). Found via lint `UnusedResources`; the report no longer lists any unused
  string. Other unused resources (7 session/current-session drawables, `preference_markdown_text.xml`, 3
  styles) remain — not part of this item.
- `[done 2026-10-16, commit 349cbd66]` The cursor-override-cannot-be-cleared precedence. Cursor style
  and blink are now stored as nullable overrides (`null` = follow `termux.properties`), the screen has
  Default chips for both, Reset stages `null`, and Apply clears the stored override instead of pinning
  a value. A colour-only Apply no longer creates a cursor override.
- (Grounding: `.superpowers/sdd/2026-10-14-in-app-terminal-appearance/progress.md:235–238`.)

## 6. Terminal background — live wallpaper

- `[limitation]` With a live (animated) wallpaper, `WallpaperManager.getDrawable()` yields no usable
  frame (black), so the system-wallpaper background works only with a static image. Ruled a
  device/engine quirk seen on the ZTE live wallpaper, not app code.

## 7. Android integration (spec §7, milestone D) — explicitly deferred

- `[deferred]` targetSdk bump (still **28**), edge-to-edge, notification permission, predictive back.

## 8. Settings & first-run onboarding (spec §7, milestone C) — IA rework shipped, root-screen tidy open

- Shipped: the B1 first-run walkthrough, B2 extra-key presets, B3 font-size default.
- Shipped (2026-10-16): the milestone C **preferences information-architecture rework**. The Termux hub is
  now Display / Keyboard / Advanced, all 13 leaf `app:key` values are frozen, and no migration is needed.
  Stream 3 commits `de9ed98b`..`9077b5f6`; spec `specs/2026-10-16-settings-ia-design.md`, plan
  `plans/2026-10-16-settings-ia.md`; final review clean (one Important fix pass).
- `[needs spec]` Still open from that spec's §7: the **root-screen tidy** — group the four plugin sections
  under a `Plugins` category (with `RootPreferencesFragment` hiding it while empty) and About/Donate under
  `About`. Also open: surfacing `termux.properties`-only options in the UI.
- `[needs spec]` A setup wizard beyond the shipped walkthrough remains unstarted.

## 9. Terminal engine upgrade

- Captured separately in `ideas/2026-10-14-terminal-engine-upgrade-idea.md` (brainstorming stopped
  before approach selection; resume at its "Open decision" section). Not repeated here.
  `[updated 2026-10-17]` Approach **B (substrate first)** chosen; workstream 1 (cell model) has a spec at
  `specs/2026-10-17-terminal-engine-cell-model-design.md` (pre-planning review). It also absorbed the parsing halves
  of `SGR 4:n` (underline styles) and `OSC 8` (hyperlinks) so the new storage ships live rather than unused;
  workstream 5 keeps xterm conformance, link click handling and the remaining OSC 8 work. Workstreams 2–6 remain
  unspecced.

## 10. Doc hygiene debt (this directory)

- `docs/superpowers/` is gitignored (`.gitignore:49`), so none of these records are in the repo — they
  are local-only.
- `[done 2026-10-16]` `plans/2026-10-14-in-app-terminal-appearance.md` now has a `## Status` section
  recording C1–C4 shipped/merged (PR #1, merge `4b3a0143`), and its header line is updated.
- `[done 2026-10-16]` The shell plan's A3 Step 2 checkbox was left unchecked but relabelled to say
  "deliberately deferred to A3/A4, not 'not yet started'"; the plan header now reads "A1–A5 shipped and
  merged" instead of "awaiting user approval to begin A1".

## 11. Non-goals — decided against, recorded so they are not mistaken for gaps

From the shell/sessions spec (§ Non-goals): no rewrite of terminal emulation, input or scrollback; no
change to `targetSdk` *within that workstream* (that is the separate §7 milestone); no change to the
package or applicationId; no `:termux-shared` public API change; `:terminal-emulator` and
`:terminal-view` compile and test unchanged.

From the appearance plan (Non-goals): no `:terminal-emulator` / `:terminal-view` changes; no
`:termux-shared` diff; no live-apply (explicit Apply was chosen); no new Gradle module and no new
third-party dependency (the font picker is hand-written).
