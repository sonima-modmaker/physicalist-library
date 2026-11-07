# Physicalist 0.7 — major physics update

## What changed

The built-in block assemblies and player test ragdolls now use a persistent
Rapier3D 0.36.1 scene per server dimension when the native backend is available.
This replaces isolated Java body steps for those entities. Dynamic bodies in
the same scene share contact solving, mass/inertia, friction, sleeping, continuous
collision detection and articulated constraints. The scene advances on the server;
clients interpolate the published entity poses. Minecraft is never accessed by a
native worker thread.

The native backend is an independent Rust/JNI implementation using upstream
Rapier. It contains no MCS/QuistaPhys code, native binaries, models or sounds.
The architectural comparison with MCS motivated this change; Physicalist does
not reproduce its weapons, signal simulation, thermal renderer or client ownership.

## Install and check

Use `physicalist_library-0.7.0.jar`, Java 21, Minecraft 1.21.1 and NeoForge
21.1.219 or a newer 21.1.x patch. Keep one Physicalist JAR in `mods`.
The internal ID remains `physicalist_library` for existing saves and dependencies.
The supplied native binary targets Windows x86-64. On other platforms, or when
native loading fails, the previous Java solver remains available. This is a
functional fallback, not a claim of identical contact behaviour.

Run `/physicalist engine` in a world. It reports the backend, body count,
sleeping/paused bodies, cached terrain sections, bake count and last scene-step
time. `Rapier3D 0.36.1 / f64 / ABI 1` confirms native loading. The same status is
written to the game log. `engine.nativeEnabled=false` in the common configuration
selects Java physics. For development, `-Dphysicalist.native.disable=true` also
disables native loading.

For a test ragdoll, run `/physicalist ragdoll` or
`/physicalist ragdoll PlayerName` for an online player. This creates a separate
six-part dummy with the player's skin; it does not modify the player. Use the
Physics Wand to pull it, the mouse wheel to adjust distance, and Tab plus mouse
movement to rotate. F3+B shows collision geometry. The assembler captures a
selected group of world blocks as one compound body, preserving voxel shapes.

## Scene and units

The Java API continues to use blocks, blocks per Minecraft tick, and radians per
tick. JNI converts linear/angular velocity to units per second (multiply by 20),
and gravity to units per second squared (multiply by 400). A block is treated as
one metre for the native scene. Native positions and calculations use f64;
Minecraft display rotations are floats. Mass is distributed over collider volume,
so off-centre assemblies have an off-centre centre of mass and corresponding
inertia rather than the bounding-box centre.

Each tick batches states into the native scene, advances contact/joint solving,
and reads poses and velocities back. Collider geometry persists until its revision
or shape changes. Small quaternion rotations use an atan2 conversion so they do
not vanish when a float quaternion's scalar component rounds to one.

World terrain is cached in 16×16×16 sections. Adjacent full cubes merge into
rectangular prisms; stairs and other custom shapes retain voxel boxes. Block
changes invalidate affected sections, and unused sections expire. Only loaded
chunks are sampled. A body whose swept collision region is unavailable pauses
instead of falling through missing terrain; this mechanism does not generate
unlimited chunks. Chunk tickets and far-distance rendering are the responsibility
of the integrating vehicle mod.

## Developer integration

Implement `PhysicsBody` as before: expose the owning entity, current world-space
oriented collision boxes, forward/up axes, angular velocity, mass, profile and a
rotation method. Keep model/collision axes consistent and suppress ordinary
Minecraft movement while Physicalist owns the body.

```java
// Once during this body's server tick, before LevelTickEvent.Post:
if (!NativeWorlds.enqueue(body)) {
    PhysicalistSimulation.step(body); // synchronous Java fallback
}
```

Import `dev.physicalist.nativecore.NativeWorlds`. The normal level post-tick event
flushes the shared native scene. Do not call `flush` for each entity in production:
that would advance the entire world once per body. Enqueue is deferred: the new
pose is available after the level flush, not immediately inside the entity tick.
Do not run both solvers for the same body. The existing
`PhysicalistSimulation.step` stays synchronous and uses the Java solver for
backward compatibility; merely installing the update cannot migrate a mod that
owns a separate physics loop.

Override `nativeGeometryRevision()` with a stable nonnegative number and increment
it when shape, scale, articulation or attached parts change. The default `-1`
checks geometry every tick, useful for compatibility but more expensive. Boxes
are converted to body-local transforms and cached. Position/rotation changes are
not geometry changes. A folded wing must change the revision because its local
collider transform has changed. Aerodynamic forces still come from the existing
manual or geometry-driven aerodynamic profile; native physics handles movement
and contacts, not automatic guidance.

`BodyForces` supplies point impulses, torques and blast helpers.
`RigidBodyState`, `ElasticJoint` and `JointSolver` remain available for custom
Java spring/socket systems. The native JNI bridge exposes compound bodies,
spherical/revolute/prismatic/fixed joints, angular/linear limits and an optional
motor for advanced integrations. Its packed ABI is low-level and versioned;
prefer the `PhysicsBody` adapter for ordinary vehicles. Native player ragdolls use
six bodies in an articulated multibody joined at neck, shoulders and hips.
Root pose/velocity changes are also applied to the articulation's generalized
state so pulling a sleeping dummy actually moves it. Self-contact is enabled;
only small attachment caps omit torso contact to keep the socket from fighting
its own overlapping shoulder/neck geometry.

For a mod with its own flight controller, override `nativeForcesEnabled()` to
return false and submit the already computed engine/aerodynamic velocity.
The scene still integrates profile gravity; cancel any gravity already included
in the submitted velocity to avoid applying it twice. `onNativeContact` receives
the collider part, contact, closing speed and impulse, including gentle support
contacts. `nativeStepCompleted` runs after the pose and contact callbacks. These
hooks let Create: The Air War keep its guidance, folding/detachment and fuzes
while using shared rigid-body contacts. Its companion build requires Physicalist
0.7.0; older Air War builds continue to use their own movement/contact loop.

## External worlds and diagnostics

`PhysicalistExternalCollisions.register` supplies moving oriented boxes, point
velocity and a reaction-impulse callback. Native scenes create kinematic proxies
and return contact impulses to the provider. A reflective optional Sable bridge
samples ship block shapes and sends reaction forces through Sable's force groups.
It does not bundle Sable. Unsupported Sable APIs disable that bridge with a log
message; real modpack testing is still required for each Sable version.

Use `/physicalist stats start`, reproduce a case, then `/physicalist stats stop`
and `/physicalist engine`. Scene time includes terrain/proxy work; a single
measurement is not a TPS benchmark. Test stacks, removed supports, thin high-speed
contacts, off-centre assemblies and folded ragdolls. Native Rust unit tests and
Minecraft GameTests live in the repository. Build instructions are in
`native/README.md`. Contact stability improvements are not a guarantee of unlimited
body count or compatibility with every external mod.
