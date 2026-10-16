# Ideas and manual checks

## Colour-preset checks on real devices

The appearance screen ships seven presets. Four are already familiar (Termux default, and the
schemes inherited from Termux:Styling); three were added without a device pass and only ever need a
human eye, because no unit test can judge whether text is comfortable to read. Run this on a phone
in both portrait and landscape, at the smallest and largest font sizes, and in bright light.

For each preset:

- [ ] **Solarized Light** — switch to it from the Style menu, then from Settings.
- [ ] **Gruvbox Dark** — same.
- [ ] **One Dark** — same.

For each, check:

- [ ] The prompt and normal output are easy to read against the background (foreground/background,
      and ANSI 0-7).
- [ ] The bright ANSI colours (8-15) stay distinguishable from their dim counterparts.
- [ ] The cursor is visible against both the background and the text it sits on.
- [ ] A selection highlight does not swallow the selected text.
- [ ] `ls --color`, a `git diff`, and `vim` (syntax highlighting) are legible.
- [ ] After picking the preset, Apply, then leave and reopen the screen: the same colours come back.

Record anything that fails here with the preset name and a screenshot, so the palette can be
adjusted rather than dropped.

## Not yet decided

- Whether the appearance screen should also surface the extended 256-colour palette; today only
  `color0`..`color15` are editable, and unmanaged keys are preserved untouched.
