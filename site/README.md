# Physicalist website

GitHub Pages publishes this directory (`.github/workflows/pages.yml`, on every push to `main` that touches `site/`).

The first screen is a 2D physics sandbox that runs in the browser. It is a toy made for the page, not the mod's
engine:

- `physics.mjs` — rigid-body solver: oriented boxes with clipped contact manifolds, warm-started sequential impulses
  with friction, island sleeping, a soft mouse joint for grabbing, rocket aerodynamics (centre of pressure behind the
  centre of mass, control fins whose authority grows with speed), motors, guidance with a proximity fuse, and blast
  impulses. Metres, kilograms and seconds, y up.
- `playground.js` — camera that follows rockets in flight, procedurally drawn pixel textures, particles (flame, smoke,
  sparks, embers, debris, dust, shockwaves, light flashes) and input.
- `physics.test.mjs` — run with `node --test site/physics.test.mjs`.

Each file in `sprites/` is a transparent side-view render of one Create: The Air War rocket. The source model and
texture paths are listed in `tools/render_ordnance.py`. To regenerate them after changing the game assets:

```powershell
python site/tools/render_ordnance.py path/to/assets/create_the_air_wars
```

The site bundles [Monocraft](https://github.com/IdreesInc/Monocraft) under its Open Font License; the license text is
in `fonts/OFL.txt`.
