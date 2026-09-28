# Developer API and in-game tools

Physicalist Library is an opt-in API. Vehicle mods own their entities,
engines, rendering, networking, persistent state, and damage rules. The
library can calculate their collision and motion without importing those
mods. Its public Java package is `dev.physicalist`.

## Main classes

| Class | Use |
|---|---|
| `CompoundCollision` | Contact and swept contact for oriented boxes (OBBs) |
| `PhysicsBody` | Adapter supplying a live entity, pose, boxes and angular speed |
| `PhysicalistSimulation` | One complete server-side physics step |
| `AutoAerodynamics` | Detect wings from the OBB geometry and compute air forces |
| `Aerodynamics` | Manual wing-area model for specialized controllers |
| `PhysicsProfile` | Per-body motion and collision coefficients |
| `AutoAeroProfile` | Per-body geometric wing detection and force coefficients |
| `PhysicalistBodies` | Access to recently stepped bodies for tools |
| `PhysicalistBodyProvider` | Opt in if you use only calculation helpers |
| `PhysicalistDragTarget` | Apply the Physics Wand's grab goal through an external flight solver |
| `PhysicalistScalable` | Opt in to scaling both collision and visuals |
| `Hinge`, `ContactHinge` | Joint and impact-driven folding motion |

## Collision geometry

A `CompoundCollision.Box` contains a world-space center, three mutually
perpendicular **unit** axes, and three positive half-extents in blocks:

```java
new CompoundCollision.Box(center, axes, half)
```

A four-block-wide, 0.05-block-thick, one-block-long wing can use
`half = {2, 0.025, 0.5}`. A zero half-extent disables contact for that
box. Keep the axes aligned with the visible model after every rotation.
The `BoxView` interface allows a custom adapter to avoid allocating
converted boxes.

`CompoundCollision.contact(a, b)` returns a normal from `b` toward `a`,
an approximate contact point, and penetration depth, or `null` when they
do not overlap. `CompoundCollision.swept(before, after, obstacle)` catches
a thin part crossing a block between solver positions. Swept testing
covers translation; very fast pure rotation still needs substeps.

## Automatic lift from collision boxes

`PhysicalistSimulation.step(body)` uses geometric aerodynamics by default.
It recognizes thin plates with sufficient area and span across the body's
`forward()` axis. Rotating, folding, removing, or scaling a collision box
changes its aerodynamic force on the next tick. Two symmetric wings can
lift a vehicle; losing one produces roll. Broad wings also slow a face-on
fall. Thick hull sections and narrow rods do not count as wings with the
default profile.

```java
@Override public List<CompoundCollision.Box> collisionBoxes() {
    return List.of(hullBox(), leftWingBox(), rightWingBox());
}
@Override public Vec3 forward() { return modelForward(); }
@Override public Vec3 up() { return modelUp(); }
@Override public double aerodynamicMass() { return 3.0; }
```

For `forward = (0, 0, 1)`, a wing with `half = {1, 0.025, 0.5}` and
X/Y/Z axes is detected. A small configured incidence angle provides
lift during level acceleration; the vehicle mod still has to supply
**thrust**. `aerodynamicMass()` defaults to `1` and must be positive.
`AutoAerodynamics.isLiftingSurface(box, forward, profile)` can diagnose
why a particular box is not recognized.

You may use the calculation without the built-in integrator:

```java
Aerodynamics.Step next = AutoAerodynamics.step(
    velocity, angularVelocity, forward, up, entity.position(),
    collisionBoxes, aerodynamicMass, damaged,
    physicsProfile, autoAeroProfile
);
```

This step includes gravity and baseline drag. Do not run it alongside
`PhysicalistSimulation.step` in the same tick or gravity is applied twice.
The old manual API remains available:

```java
Aerodynamics.Step next = Aerodynamics.step(
    velocity, angularVelocity, forward, up,
    wingArea, imbalance, stabilizers, damaged, physicsProfile
);
```

To keep the manual mode in `PhysicalistSimulation`, override
`automaticAerodynamics()` to return `false`. Override `autoAeroProfile()`
or `profile()` to tune individual vehicle types. The common TOML provides
cached defaults; do not rebuild profiles on every collision substep.

## Server-side body adapter

```java
public final class VehicleBody implements PhysicsBody {
    private final Entity vehicle;
    private Vec3 angular = Vec3.ZERO;

    public VehicleBody(Entity vehicle) { this.vehicle = vehicle; }
    @Override public Entity entity() { return vehicle; }
    @Override public List<CompoundCollision.Box> collisionBoxes() {
        return currentOrientedModelBoxes();
    }
    @Override public Vec3 forward() { return currentForward(); }
    @Override public Vec3 up() { return currentUp(); }
    @Override public Vec3 angularVelocity() { return angular; }
    @Override public void setAngularVelocity(Vec3 value) { angular = value; }
    @Override public void rotate(Vec3 speed, double fraction) {
        rotateModelBy(speed.scale(fraction));
    }
    @Override public void onImpact(int part, CompoundCollision.Contact hit,
                                   double normalSpeed) {
        handleVehicleDamage(part, hit, normalSpeed);
    }
}
```

`currentOrientedModelBoxes`, `currentForward`, `currentUp`,
`rotateModelBy`, and `handleVehicleDamage` are **your mod's methods**, not
members of this library. Create an adapter for your own model. Call
`PhysicalistSimulation.step(body)` once on the **server** each tick and
suppress the entity's usual movement that tick. The solver adds gravity,
drag and lift, integrates pose in substeps, tests world blocks, applies
contact response and calls `onImpact` above the configured speed.

The adapter owns network interpolation, saving angular velocity, loading
faraway chunks and any entity-to-entity or moving-structure collisions.
When a future chunk is not loaded, this generic solver stops the current
step; it does not request a chunk ticket. The number of collision boxes
must stay constant *within* a substep. A lost part may be represented by
a zero-volume box until the next tick.

## Creative tools and commands

In creative mode, use the **Physics Wand** to hold and drag any loaded,
currently simulated body. Bodies run through `PhysicalistSimulation`
are tracked automatically. If your mod uses only `AutoAerodynamics.step`,
`Aerodynamics.step`, or `CompoundCollision` directly, implement
`PhysicalistBodyProvider` on its entity and return your `PhysicsBody`.
Otherwise the library cannot safely discover or manipulate it.
If an existing flight solver owns velocity and impulses, also implement
`PhysicalistDragTarget` so the wand passes a world-space grab goal to that
solver. This prevents the wand and engine from overwriting each other's
motion. Mods with their own F3+B outline can return `false` from
`physicalistRenderDebugCollision()` to avoid duplicate lines.

The **Block Assembler** selects two opposite corners with right clicks, then
turns every non-air block in that region into **one** `PhysicalBlockEntity`.
Sneak-right-click clears the first corner. It captures each block's voxel
shape before removing the world blocks. The assembled body renders all source
states, responds to off-center impact with angular motion, and can be pushed
by nearby entities. F3+B draws each oriented collision box in green. The
selection is limited to 128 blocks, 16 blocks along each axis, and 512 voxel
collision boxes. Blocks with block entities or fluid states are rejected.
The **Physical Entity Deleter** removes the aimed body without returning its
source blocks. The wand and deleter select the actual oriented collision
parts, rather than empty space within the entity's broadphase box.

Operator commands (permission level 2):

```text
/physicalist list
/physicalist select <entity_id>
/physicalist select look
/physicalist info
/physicalist scale <0.1..16>
/physicalist delete
/physicalist block <x> <y> <z>
/physicalist assemble <x1> <y1> <z1> <x2> <y2> <z2>
/physicalist spawn <block_id> <x> <y> <z>
```

`list` reports loaded Physicalist bodies. `select` stores a selected
entity for the player; `info`, `scale`, and `delete` use that selection.
`block` converts a single existing world block. `assemble` converts a selected
region into one compound body. `spawn` creates a physical copy of a block's
default state without removing a world block. These commands use each block's
collision voxel shape, falling back to its selection shape when collision is
empty.

Only entities implementing `PhysicalistScalable` can be scaled. The
physical block does. An external entity must implement this interface
to update its **visual model and collision geometry together**; changing
just its vanilla bounding box would be misleading. Deletion and dragging
work on all discoverable bodies without scaling support.

## Hinges and limits

`Hinge.step(appliedTorque, restPosition, stiffness, damping,
resistance, maxRate)` advances a joint between its configured limits.
`ContactHinge.pressureDelta(...)` converts an impact into hinge travel.
The vehicle mod still decides which part breaks, folds, latches, or
returns to rest, and saves that state.

The built-in simulation checks world blocks and applies both linear and
angular impact impulses. The built-in block assembly also handles nearby
entity pushes and walking contact. Generic vehicle adapters still own Sable
ship contacts and any specialized entity-to-entity solver. `onImpact` does
not create explosions or sounds.
See [configuration](CONFIG.md) and [troubleshooting](TROUBLESHOOTING.md)
before tuning large numbers of fast-moving bodies.
