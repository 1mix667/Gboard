# Gboard+ logo

Derived from the installed Gboard app icon and recoloured for Gboard+.

## Pipeline

`build_logo.py` reads the pulled Gboard adaptive-icon layers from `source/res/` and writes:

- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`
  - `ic_launcher.png`, `ic_launcher_round.png` (legacy + round)
  - `ic_launcher_foreground.png`, `ic_launcher_monochrome.png` (adaptive layers, 108 dp)
- `generated/`
  - `ic_launcher-web.png` (512), `gboardplus.svg` (editable), `glyph_only.png`, `preview_*.png`

`app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` compose the adaptive icon, with
`@color/ic_launcher_background = #F03971`.

## Design philosophy

One idea, two voices: the **Gboard mark** stays the hero (shape untouched), and a single quiet
**`+`** in the brand colour rides the **top-right of the G**, fully inside the white mark — the place
an exponent lives — so it reads as "G, plus more" rather than a second logo. Flat, two colours
(accent `#F03971` + white), no gradient, no shadow, generous negative space. The accent doubles as
the tile background **and** the `+`; the `+` is inset onto the white badge so it never touches the
background.

## What it does

1. Loads the Gboard foreground glyph (`source/res/aQv.webp`) and thresholds out the source's faint
   ghost square.
2. Recolours the background to **`#F03971`**.
3. Adds a **`#F03971` `+`** inside the measured white area of the mark's top-right (so it stays on
   the white badge across every density).

## Source assets

`source/res/{aQv.webp (foreground), uRn.webp (solid blue bg), cWf.xml (monochrome), 31D.xml (round)}`
pulled from Gboard `18.4.1.985164140-release-arm64-v8a` on OnePlus CPH2747 (Android 16) via:

```
adb connect 192.168.100.245:6666
adb pull /data/app/.../com.google.android.inputmethod.latin-.../base.apk
```

## Regenerate

```bash
python build_logo.py
```

## Trademark

The glyph is Google's Gboard mark. Personal, non-commercial use; replace with an original mark
before redistribution.
