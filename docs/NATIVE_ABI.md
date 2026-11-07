# Native bridge ABI 3

Prefer the `PhysicsBody` / `NativeWorlds.enqueue` adapter documented in
[PHYSICS_0_7.md](PHYSICS_0_7.md). The following packed API is for advanced,
trusted server-side integrations. It is not a network protocol. Do not pass
client-supplied scene/body IDs or unchecked arrays into JNI.

`NativeBridge.AVAILABLE` and `STATUS` report loading. `abi0()` must return 3.
Scene and body handles belong to one native scene and become invalid on removal
or `destroyScene0`. Always close scenes on world unload. The built-in world
manager handles that lifetime automatically.

## Bodies and state batches

`createScene0(gravity)` takes nonnegative downward acceleration in blocks/s².
`createBody0(scene,mass,gravityScale,owner,role,state,geometry)` creates a dynamic
body for positive mass, or a velocity-driven kinematic proxy for zero mass.
`owner` identifies related colliders for attachment filtering. Use `role=-1`
for ordinary bodies; 0–5 are the test ragdoll's head, torso, arms and legs.

Each body state has 15 doubles:

| Offsets | Value |
|---|---|
| 0–2 | World position, blocks |
| 3–6 | Normalized quaternion x/y/z/w |
| 7–9 | World linear velocity, blocks/s |
| 10–12 | World angular velocity, rad/s |
| 13 | Gravity scale relative to scene gravity |
| 14 | Input flags, or output sleep flag |

`sync0(scene,ids,states)` concatenates one state per ID. Input flags are bit 1
for changed pose, bit 2 for changed velocity, bit 4 for enabled and bit 8 for
disabled. Output from `read0` uses offset 14 = 1 for sleeping, 0 otherwise.
Avoid resending unchanged poses/velocities: it wakes bodies and defeats sleeping.
Equivalent quaternion signs must be aligned before comparing changes.

`step0(scene,seconds)` accepts a positive step up to 0.1 s. The built-in manager
uses 0.05 s per Minecraft tick, subdivided into eight native steps with CCD.
Never step the shared scene once per entity. Articulated roots also update their
generalized velocity when a changed root velocity is submitted; initial root
momentum is preserved when links are attached.

## Geometry, terrain and contacts

Compound geometry uses 12 doubles per oriented box: local centre x/y/z,
local quaternion x/y/z/w, positive half extents x/y/z, friction and restitution.
Mass is distributed by volume. `geometry0` replaces an existing body's boxes
and recalculates mass properties. `removeBody0` also removes attached joints.

`terrain0(scene,key,boxes)` replaces a static terrain tile. Each box uses six
world-space values: min x/y/z, max x/y/z. An empty array removes the tile.
The Java terrain manager provides section caching and invalidation; direct JNI
callers must manage their own tile residency.

`contacts0(scene,body)` returns accumulated contacts from the last scene step,
10 doubles each: own collider index, other native body ID (0 for static terrain),
outward world normal x/y/z, world point x/y/z, penetration depth and normal impulse.
Impulse is in native mass×blocks/s units. Divide by 20 to use the tick-based Java
impulse helpers. Contacts are bounded and aggregated across substeps; this is a
contact notification stream rather than a complete mesh of every touching point.

## Joints

`joint0(scene,a,b,spec)` accepts:

| Offsets | Meaning |
|---|---|
| 0 | Type: 0 spherical, 1 revolute, 2 prismatic, 3 fixed |
| 1–3 / 4–6 | Local anchor on body A / B |
| 7 / 8 | Revolute/prismatic lower / upper limit |
| 7 / 9 | Spherical twist bound / coupled swing bound; negative disables |
| 10 / 11 | Motor stiffness / damping; positive stiffness targets position zero |
| 12–14, optional | Nonzero local principal axis; defaults to +X |
| 15, optional | Nonzero selects articulated multibody instead of impulse joint |

With spherical type and zero stiffness, positive damping requests zero relative
angular velocity. Spherical swing couples the two tangent axes; it does not use
three unrelated Euler-angle clamps. Articulated joints require an acyclic parent
graph. Ragdolls use a +Y principal axis and damping at the sockets.

This low-level version creates joints and removes them with their bodies; it
does not expose a complete runtime joint editor. For configurable Java springs,
break thresholds and custom projection systems use `ElasticJoint`,
`RigidBodyState` and `JointSolver` from the [elasticity guide](ELASTICITY.md).
Native bridge errors are logged and cause the world manager to resume Java
physics; always check `/physicalist engine` when diagnosing an integration.

## Forces (ABI 2)

`forces0(scene,body,wrench)` accepts six finite doubles (force x/y/z in N,
torque x/y/z in N·m), or 15 doubles: aerodynamic force and torque at 0–5,
external propulsion force and torque at 6–11, world wind velocity in m/s at 12–14.
Loads apply for the next scene step only and are cleared afterwards. The 15-value
form is applied after state synchronization and limits aerodynamic work using
native inverse mass/inertia; this prevents a discrete drag step from creating
kinetic energy relative to the wind. Propulsion is not subject to that limit.
Forces are world-space and torque is around the native centre of mass.

ABI 3 adds jointForce0 (world force on body B in N) and ignore0 (temporary collision exclusion for fully contained colliders). Joint spec offset 16 disables collision within a joined pair. Spherical articulation preserves and accepts child angular state.
