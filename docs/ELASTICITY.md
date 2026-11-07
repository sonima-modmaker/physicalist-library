# Physicalist 0.6: elastic rigid bodies and developer guide

This release adds reusable XPBD joints, a six-part player ragdoll test entity, an obstacle spatial index and opt-in server-side counters. The public mod ID remains `physicalist_library`. Existing `PhysicsBody` adapters do not need new methods. Minecraft remains 1.21.1 / Java 21; the minimum declared NeoForge version remains 21.1.219.

## Try it in a world

Install the same Physicalist version on the server and client. Use an operator account or enable cheats. Save a copy of a test world before experimenting with a new physics release.

```mcfunction
/physicalist ragdoll
/physicalist ragdoll YourPlayerName
/physicalist ragdoll @p
/physicalist list
/physicalist select look
/physicalist info
/physicalist delete
```

Without an argument the command uses your own skin. The optional argument selects one **online** player through Minecraft's ordinary player selector. It creates a separate, nonliving test dummy near the command source; it does not kill, replace, immobilise or change the selected player's inventory. An offline nickname is not resolved by this command. A console can specify an online player; use `execute positioned` to control its spawn location.

The dummy has six rigid parts: head, torso, two arms and two legs. Each part has its own position, quaternion, velocity, mass and box inertia. Five elastic ball sockets connect the parts. Cone limits resist unnatural bends; twist remains free. Limbs collide with each other and the torso. Only a small neck/shoulder/hip attachment cap is trimmed for connected-pair contacts, allowing the shared joint anchor to move without fighting collision projection. Positional contact correction does not reconstruct velocity; zero-restitution contact impulses and velocity-level socket constraints prevent overlap recovery from injecting spin. World and external-ship contacts remain active. This is a six-part test rig, not an anatomical skeleton with elbows, knees, muscles or cloth.

Use the creative physics wand to grab and move it. Mouse wheel adjusts grab distance; hold Tab and move the mouse to rotate. The deleter and `/physicalist select` recognise the same `PhysicalistBodyProvider` interface as ordinary physical assemblies. F3+B displays the six actual boxes in green. The skin uses the standard 64×64 player UV layout and classic-width arms. Slim skins are displayed on classic arm geometry. Armour, cape, equipment and the outer skin layer are not rendered by this test renderer.

The server owns the simulation. Clients interpolate limb positions and quaternion rotations from periodic snapshots; they do not run a competing ragdoll solver. Owner skin properties, all six poses, linear/angular velocities and age survive saving. A dummy expires after 12,000 loaded ticks (10 minutes at 20 TPS). Creation is limited to 32 test dummies within 128 blocks of the command source. This local guard is not a global server quota.

## What elasticity means

`ElasticJoint` constrains two local anchor points. A distance joint keeps them near a rest length, while a socket uses a rest length of zero. Bodies remain rigid: the joint stretches and rotates; the mesh itself does not deform. Existing `Hinge`/`ContactHinge` APIs remain available for contact-driven folding parts.

The positional solver uses extended position-based dynamics:

```
alpha = compliance / (dt * dt)
deltaLambda = (-constraintError - alpha * lambda) / (effectiveInverseMass + alpha)
```

The effective mass includes both translation and rotation about the anchors. Corrections act on both bodies using their inverse masses and world-space inverse inertia. `lambda` accumulates across iterations of one substep and resets at the next substep. This helps keep stiffness consistent when the iteration budget changes; it does not make arbitrary timesteps equivalent or remove the need for substeps.

- `compliance = 0`: rigid positional connection.
- Larger compliance: more stretch under load.
- `damping = 0`: no joint damping.
- Damping towards `1`: stronger suppression of relative anchor motion.
- `breakForce`: break when the solver's force estimate `abs(lambda) / dt²` exceeds this threshold. Infinity disables breaking.
- `limit(axisA, axisB, angle)`: limit the angle between two local axes in radians. It is a swing cone, not a twist stop or hinge motor.

Do not multiply compliance by iteration count. Do not reset lambda inside every iteration. Call damping once per substep, not once per iteration. The solver handles an exactly opposite pair of cone axes with a deterministic fallback axis, avoiding a zero-cross-product singularity.

## Units and state

Distances are blocks. Time is Minecraft ticks. Velocity is blocks/tick; angular velocity is radians/tick. Gravity is blocks/tick². To convert a velocity in blocks/second divide by 20; to convert acceleration in blocks/second² divide by 400. Mass is relative: choose one convention and use it for every connected body. An impulse has mass×blocks/tick units.

`RigidBodyState(position, halfExtents, mass)` stores a quaternion orientation, world-space position, linear velocity and angular velocity. **Half extents** are half the box dimensions. Mass zero creates a static anchor; negative mass, nonfinite numbers and nonpositive geometry are rejected. The box inertia tensor follows the dimensions; `inverseInertia(torque)` rotates torque into local space, applies the diagonal inverse tensor, then returns to world space.

Useful operations:

| Method | Purpose |
| --- | --- |
| `point(local)` / `vector(local)` | Transform an anchor / direction into world space |
| `pointVelocity(world)` | Linear plus angular velocity at a point |
| `impulse(j, point)` | Apply translation and torque from a point impulse |
| `positionImpulse(j, arm)` | Apply a positional constraint correction |
| `integrate(dt, acceleration)` | Advance one free rigid-body substep |
| `velocityFromPose(oldPosition, oldRotation, dt)` | Reconstruct velocities after positional projection |
| `box()` | Obtain an oriented `CompoundCollision.Box` |
| `ElasticJoint.error()` / `broken()` | Inspect constraint error and break state |
| `BodyForces.force` / `impulse` / `explosion` | Apply mass-aware forces to existing `PhysicsBody` adapters |

State is mutable and belongs to the simulation thread. Do not share a `RigidBodyState` across asynchronous workers. `BodyForces` uses a scalar box inertia estimate for legacy entity adapters; `RigidBodyState` provides the full rotated box tensor. These are intentionally distinct APIs.

## Minimal spring example

```java
var fixed = new RigidBodyState(new Vec3(0, 5, 0), new Vec3(.1, .1, .1), 0);
var weight = new RigidBodyState(new Vec3(0, 3, 0), new Vec3(.25, .25, .25), 1);
var spring = new ElasticJoint(fixed, weight, Vec3.ZERO, Vec3.ZERO,
        2.0, .001, .2, Double.POSITIVE_INFINITY);
var bodies = List.of(fixed, weight);
var joints = List.of(spring);

// Called on the server once per tick, with four substeps:
for (int substep = 0; substep < 4; substep++) {
    JointSolver.step(bodies, joints, new Vec3(0, -.024525, 0), .25, 8,
            () -> projectYourWorldContacts(bodies));
}
```

`projectYourWorldContacts` is **your integration callback**, not a method supplied by Physicalist. It must project penetrations against loaded world collision shapes after every constraint iteration. Cache nearby world boxes once per tick where appropriate. After `JointSolver.step`, apply contact restitution/friction impulses appropriate to your bodies. The driver integrates bodies, solves joints, calls your collision projector, reconstructs velocities and damps joints. It does not automatically create entities, perform networking, collide unrelated islands, load chunks or simulate a fluid.

To connect a shoulder, use local anchors at the shoulder in each body:

```java
var shoulder = ElasticJoint.socket(torso, arm,
        new Vec3(.25, .30, 0), new Vec3(-.125, .30, 0), .00008, .22)
        .limit(new Vec3(0, 1, 0), new Vec3(0, 1, 0), Math.toRadians(145));
```

Store joints alongside the bodies they reference. Persist the bodies and joint parameters yourself; `ElasticJoint` is not a world registry or automatic NBT serializer. Reconstruct joints after loading their bodies. A broken joint no longer projects positions or applies damping. Adjacent linked bodies usually need their collision pair filtered to avoid competing constraints. The test ragdoll demonstrates this.

## Optimisation and measurement

`CollisionIndex<T>` builds a four-block spatial grid over immutable per-step obstacle bounds. Queries return a conservative candidate list for SAT. Objects spanning more than 512 grid cells use a separate large-object list; queries spanning more than 4096 cells use a full-list fallback. Lists below 64 objects also use a full-list scan, avoiding grid overhead in small scenes. Grid entries are deduplicated by identity. Negative coordinates and large queries are covered by regression tests.

The generic entity solver builds this index once for the cached obstacle set and queries it for each swept part. It requires at least 64 obstacles and an estimated budget of at least 64 part/substep queries before paying for a grid; smaller workloads retain the linear scan. It invalidates both the obstacle cache and index after an impact callback, because that callback may destroy blocks. If a callback removes the body, simulation stops immediately. Rebuild an index when obstacle bounds change: it is not a persistent moving-world broad phase.

The body registry no longer evicts live registrations merely because another dimension is stepping its own bodies. Stale and removed bodies still expire. Ragdolls share one nearby obstacle snapshot across their limb iterations, sleep when settled on ordinary terrain, check support periodically while asleep, wake on impulses/grabs, and do not force chunk loading. Moving external colliders keep the ragdoll awake. External providers still supply optional Sable collision geometry and receive normal reaction impulses.

```mcfunction
/physicalist stats start
# Run the same scene for a fixed interval.
/physicalist stats
/physicalist stats stop
/physicalist stats reset
```

Counters report accumulated solver time, step count, mean time per step, broadphase candidate count and detected contacts. Disabled timing does not call `nanoTime`. Timings include the generic Physicalist solver and the test ragdoll; another mod's entirely custom integrator is not automatically measured. This is not total Minecraft MSPT, a percentile profiler, GPU timing or an automatic TPS guarantee. For a fair comparison use the same world, body count, duration and hardware, warm up both versions, and inspect server MSPT separately. The synthetic broadphase test checks correctness and candidate reduction, not a claimed percentage improvement in a real modpack.

## Integration and limits

Implement `PhysicalistBodyProvider` so commands, targeting and the debug renderer can discover your body. Use `PhysicalistDragTarget` when you need to distribute a wand goal across an articulated structure; otherwise the wand uses the root entity's velocity. Your implementation must wake sleeping parts. Use quaternion interpolation on clients. A compound bounding box is only a broad phase; use the actual oriented parts for impact checks.

Keep all world access on the server thread and only query loaded chunks. The test ragdoll pauses at unloaded terrain instead of generating chunks. It does not provide the Air War mod's long-distance projectile replication. `PhysicalistExternalCollisions.register` is the optional moving-world bridge; Physicalist itself has no hard Sable dependency. The ragdoll currently transfers normal contact reaction to an external provider; complete articulated mutual dynamics and tangential reaction on a moving ship need integration-specific tuning.

This is elastic **rigid-body** physics, not cloth/FEM soft-body deformation. Very stiff joints, long chains, enormous mass ratios, tiny limbs at high speeds and dense piles need higher budgets and measured tuning. More iterations have a TPS cost. The test dummy deliberately uses six parts, bounded substeps and bounded lifetime. Do not promise an unbounded number of active ragdolls at 20 TPS.


### Ragdoll contact correction in 0.6.2

`ElasticJoint.solveVelocity(strength)` solves anchor velocity constraints without converting positional depenetration into velocity. It preserves paired linear momentum and suppresses outward motion at active swing limits. The test ragdoll interleaves it with zero-restitution self/world contacts. Use this for rigid sockets; applying it at full strength to a compliant distance spring will also remove its axial oscillation. The general `JointSolver` retains its XPBD velocity-reconstruction behavior for springs.
