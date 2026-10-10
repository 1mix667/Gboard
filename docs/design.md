# Gboard+ — Design

- **Status:** Draft
- **Related:** [requirements.md](requirements.md) · [../logo/build_logo.py](../logo/build_logo.py)

## 1. Brand

| Token | Value | Use |
|-------|-------|-----|
| Primary / Accent | `#F03971` | Logo background, headings, active controls |
| Primary tint | `#F03971` @ 10% | Active card backgrounds |
| Surface | `#FFFFFF` | Cards, settings background |
| Warning tint | `#FF9800` @ 8% | "not loaded" / handoff cards |
| Positive tint | `#F03971` @ 10% | "loaded" card |
| Text dim | `#777777` / `#999999` | Secondary text |

**Name:** Gboard+ · **Package:** `io.github.kvmy666.gboardplus` · **Log tag:** `GboardPlus`

## 2. Logo

The logo clones the Gboard app icon and adds a `+`.

- **Philosophy:** one idea, two voices. The Gboard mark stays the hero (shape untouched); a single
  quiet `+` in the brand colour rides the **top-right of the G**, fully inside the white mark, so it
  reads as "G, plus more". Flat, two colours (`#F03971` accent + white), no gradient/shadow, generous
  negative space.
- **Source (pulled from device):** Gboard `18.4.1` adaptive icon — foreground `aQv.webp` (the
  "G"/keyboard glyph), background solid Google blue, monochrome `cWf.xml`.
- **Transformation (`logo/build_logo.py`):**
  1. recolour background to `#F03971`;
  2. trim the glyph's transparent margins so the mark fills the tile; drop the source ghost square;
  3. add a `#F03971` `+` inside the measured white area of the mark's top-right (stays on the white
     badge and inside the adaptive safe zone).
- **Outputs:** legacy `ic_launcher.png` + `ic_launcher_round.png` (mdpi→xxxhdpi), adaptive
  `ic_launcher_foreground.png` + `ic_launcher_monochrome.png`, `ic_launcher-web.png` (512),
  editable `gboardplus.svg`, and previews.

> **Trademark/copyright note:** the glyph is derived from Google's Gboard application icon.
> This is a derivative for a personal, non-commercial module. Redistribution may infringe
> Google's marks/copyright; if Gboard+ is published, replace the glyph with an original mark.

## 3. Settings app — screens

Single scrollable screen (`MainActivity` → `SettingsScreen`), Material 3, accent `#F03971`.

```
┌───────────────────────────────────────────┐
│ Gboard+                                    │
│ Clipboard vault, shortcuts and cursor …    │
├───────────────────────────────────────────┤
│ [ Liveness card ]                          │
│  • "Checking Gboard…" | "loaded" | "not    │
│    loaded"                                 │
│  • not loaded → [Force stop Gboard] /      │
│    [Open Gboard app info]  + [Re-check]    │
├───────────────────────────────────────────┤
│ [ Auto Expand handoff card ]  (only when   │
│   AE's Gboard tweak is detected as ON)     │
│  • [Disable it for me (root)] /            │
│    [Open Auto Expand]                      │
├───────────────────────────────────────────┤
│ Toolbar                                     │
│   Enable keyboard toolbar            [ •]  │
│   Toolbar height (×)                 1.0   │
│   Button size (×)                    1.0   │
│ Buttons                                     │
│   Clipboard / Paste / Select all / …  [ •] │
│ Clipboard vault                             │
│   Max entries                        500   │
│   Show full text in rows             [ •]  │
│ Images                                      │
│   Save copied images / screenshots   [ •]  │
│ Undo & haptics                              │
│   Undo / Shake to undo / Trackpad …  [ •]  │
│   Vibration strength / Sensitivity  100/1.0 │
│   Root DPAD fallback                 [ •]  │
└───────────────────────────────────────────┘
```

### 3.1 Liveness card (states)
| State | Heading | Controls |
|-------|---------|----------|
| `unknown` | "Checking Gboard…" | none |
| `loaded` | "Gboard+ is loaded in Gboard" | none (card auto-clears controls) |
| `notLoaded` | "Gboard+ is not loaded in Gboard yet" | root: **Force stop Gboard**; no-root: **Open Gboard app info**; always **Re-check** |

### 3.2 Auto Expand handoff card (states)
Shown only when AE's `keyboard_enhancer_enabled == "1"`.
| Root | Controls |
|------|----------|
| yes | **Disable it for me (root)** (flips AE off, enables Gboard+), **Open Auto Expand** |
| no | **Open Auto Expand** (manual step) |

## 4. Toolbar (in-keyboard UI)

- Injected inside Gboard's keyboard content `LinearLayout` so the keyboard window grows naturally.
- Buttons (left→right, as enabled): Clipboard, Paste, Select-all, Cursor, Trackpad,
  Select-mode, Shortcut.
- Height = 0.5 × key-row height × `toolbar_height_multiplier`; glyph = 16 sp ×
  `toolbar_button_multiplier`.
- Popups: clipboard-history popup and selection action bar are Material-styled overlays.

## 5. Interaction & feedback

- Every toggle change writes the pref and re-publishes the snapshot; the hook picks it up within
  its ~2 s refresh, or immediately at the next input-view creation.
- Root actions show a brief busy state; non-root actions deep-link and instruct.
- All strings are localised-ready (in `strings.xml`); the primary language is English.

## 6. Accessibility

- Switch rows expose label + role for TalkBack.
- Minimum touch target 48 dp for toolbar buttons.
- Colour is never the sole signal (cards carry text, e.g. "not loaded").

## 7. Assets inventory

```
logo/
  source/res/{aQv.webp,uRn.webp,cWf.xml,31D.xml}   # pulled Gboard icon layers
  build_logo.py                                     # generator
  generated/{ic_launcher-web.png,gboardplus.svg,preview_*}.png
app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png
app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
app/src/main/res/values/colors.xml                  # ic_launcher_background = #F03971
```
