# Physicalist website

GitHub Pages publishes this directory. The first screen is a small, independent
2D flight playground; it does not run Minecraft or claim to reproduce the full
server-side physics library.

Each file in `sprites/` is an individual transparent, centred side-view render
of one Create: The Air War rocket. The source model and texture paths are listed
in `tools/render_ordnance.py`. To regenerate them after changing the game assets:

```powershell
python site/tools/render_ordnance.py path/to/assets/create_the_air_wars
```

The script follows the game renderer's vanilla/non-vanilla face layouts, UV
rotation, element rotation and transparent texture alpha. It keeps only the
left-facing side so the reverse faces of thin wings do not overlap. The C-75
flat-fin underside UVs are mirrored to correct the source pair's inverted
orientation in a single-side image. Sprites are separate PNG files so the
gallery and canvas can load only what they need.

The site bundles [Monocraft](https://github.com/IdreesInc/Monocraft) under its
Open Font License; the license text is in `fonts/OFL.txt`.
