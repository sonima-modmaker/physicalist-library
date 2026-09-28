# Troubleshooting

## The game reports a missing or incompatible library

Install `physicalist_library-0.4.1.jar` in the **same instance** as the
dependent mod. Remove older Physicalist JARs from `mods`; multiple versions
share one mod ID. Do not install the `-sources.jar`. Check Minecraft 1.21.1,
NeoForge 21.1.219–21.1.251, and the dependent mod's `versionRange`.

## A vehicle ignores gravity or auto-aero

Installing the library does not intercept arbitrary entities. The vehicle
must call `PhysicalistSimulation.step` or one of the calculation APIs on
the server. It must not perform a second ordinary movement/gravity step in
the same tick. For automatic lift, pass world-space OBBs for the current
model, a correct `forward()` and `up()`, a positive aerodynamic mass and
the entity's real velocity. The vehicle's engine or controller still
provides thrust.

Call `AutoAerodynamics.isLiftingSurface` with an expected wing and the
active profile. If it returns false, check thickness, area, aspect ratio,
and axis directions. A decorative model face with zero collision thickness
cannot be an aerodynamic plate. Also make sure `automaticAerodynamics()`
was not overridden to `false`.

## A car takes off too abruptly or never lifts

Compare wing area and `aerodynamicMass()`. More mass reduces acceleration;
more speed increases aerodynamic force approximately with speed squared.
Adjust `autoAero.liftCoefficient`, `maximumLift`, or `incidenceDegrees` in
small increments. A vehicle with no engine cannot take off on its own.
At zero forward speed, a broad wing primarily resists a fall rather than
producing forward-flight lift.

## The Physics Wand cannot grab a body

The wand is creative-only and reaches 64 blocks. Aim at the entity's
broadphase bounding box and hold right click. A body passed through
`PhysicalistSimulation` is tracked automatically while it is loaded and
simulating. If a mod uses only individual Physicalist math helpers, its
entity must implement `PhysicalistBodyProvider` to expose a `PhysicsBody`.
An unrelated vanilla entity is intentionally not selectable.

For Create: The Air War rockets or debris, update **both** mods: Physicalist
Library 0.4.0 or newer and a Create: The Air War build compiled against it.
The older Create: The Air War build does not expose its physical projectiles
to the wand, deleter or `/physicalist` commands. Only loaded entities can be
selected; `/physicalist list` shows which bodies the tools can currently see.

If the entity is visible but frozen beyond loaded chunks, the owning mod
must arrange chunk loading or choose an unload policy; the library does
not issue chunk tickets. A custom flight controller may also override
the velocity set by the wand, in which case that mod must coordinate its
engine and guidance with grabbing.

## Scaling says it is unsupported

`/physicalist scale` changes both visual size and collision only for
entities implementing `PhysicalistScalable`. The built-in physical block
does. Another mod has to add that interface to its own entity and update
its model and OBBs together. Scaling just the vanilla entity AABB would
make the selection box larger while leaving its true physics unchanged.

## The assembler refuses a selection

Right click the first corner, then right click the opposite corner. Sneak-right
click clears the first corner. The selected region must be loaded and contain
1–128 non-air blocks, fit within 16 blocks along each axis, and contain no
more than 512 voxel collision boxes. Each block needs a nonempty collision
shape or a nonempty selection shape as fallback. Blocks with block entities
(such as chests) or fluid states are rejected because their data cannot be
preserved safely. Creative mode and permission to modify every selected block
are also required.

## A thin part passes through a block

Every OBB needs three positive half-extents and mutually perpendicular
unit axes. Centers and axes must be in world coordinates. Update them
after rotating the visible model. `CompoundCollision.swept` catches
translation between positions, but pure rotation still depends on
substeps. Try a smaller `collision.substepDistance`, then measure TPS.

## The body sticks, jitters, or accelerates twice

Check that its original tick does not move it again after the generic
simulation. Ensure client code interpolates server state rather than
running independent physics. Lower excessive restitution or contact slop
only after confirming there is a single owning motion step. For bodies
created from blocks, compare the source voxel shape with the visible block.

## Damaged wings do not affect flight

In automatic mode, the adapter must rotate, alter, or remove the damaged
wing's collision OBB. A visual-only damage animation changes nothing.
In manual mode, update `wingArea`, `imbalance`, `stabilizers`, or the
damaged flag. The library cannot infer damage from an external model file
that the adapter never sends.

## Client or server crashes while loading

Keep the crash report and `latest.log`. First verify a single matching
Physicalist JAR and supported versions. Then reproduce with the library
and one dependent mod, and add other mods back gradually. A successful
Gradle build proves compilation, not every renderer, packet, and game
interaction at runtime. File a report with the versions, command/tool
action that triggered it, and the relevant stack trace.
