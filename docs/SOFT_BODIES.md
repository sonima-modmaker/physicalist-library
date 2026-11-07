# Soft bodies (experimental)

Physicalist can simulate deformable bodies — cars that crumple, planes whose wings flex — in a native solver
(`native/src/softbody.rs`) on its own thread per dimension. The feature is **experimental and off by default**.

## Turning it on (players)

In `config/physicalist_library-common.toml`:

```toml
[experimental]
softBodies = true
```

Then the **Physicalist** tool appears in the creative tab. Right-click into the air opens the soft-body menu: pick a
model file (GLB/glTF, OBJ, FBX, JSON, bbmodel) or a built-in one (trainer plane, car), its scale and launch speed,
and spawn it. Right-click a door to open it, Shift+right-click removes the body, R respawns it. A model with a seat
seats the player; planes fly with the keyboard.

The config switch only gates the player-facing tool and spawning packets. `SoftApi` (below) works for other mods
regardless of it.

## How a model becomes a soft body

`SoftStructure.build(model, scale)` samples the surface into nodes about one spacing apart, joins neighbours with
beams (crossing beams give shear and bending stiffness), groups nodes into shape-matching clusters per named part,
and gives every surface node an aerodynamic patch. Render vertices are pinned to three nodes, so the drawn mesh bends
with the structure. The same model always gives the same structure, so server and clients agree.

**Mesh names drive the physics** (case-insensitive substrings):

| Name contains | Meaning |
|---|---|
| `engine` | heavy part (engine mass) |
| `seat` (not `back`) | where the pilot/driver sits |
| `propeller`, `spinner` | the rotor: spun by the engine, blade-element thrust, drawn spinning; no nodes |
| `aileron`, `elevator`, `rudder` | control surfaces (hinge at their front edge) |
| `wing`, `flap`, `stabilizer`, `tailplane`, `fin` (+ the control surfaces) | on an aircraft: springy wing structure |
| `wheel` | rolling nodes (and drawn rolling over the ground) |
| `gear`, `strut` | springy landing gear |
| `door` | a hinged door that swings up to 90° and latches |

A model counts as an **aircraft** when it has a propeller or a control surface. Then:

- wings and tailplanes are purely elastic: they flex under load and always spring back, never keeping a bend;
- a wing's root is brittle: above 8 % strain it snaps and the wing tears off whole (a crash, not flight);
- fuselage skin gets 40 % of a flat plate's aerodynamic force (a slender body lifts far less than a wing).

Surfaces shut in by the model's own skin (cabin insides, seats, double walls) get no aerodynamics.

## API for other mods (`dev.physicalist.soft.SoftApi`)

```java
if (SoftApi.available()) {
    SoftStructure s = SoftStructure.build(model, 1);           // or build(model, scale, totalMassKg)
    double[] nodes = ...;                                      // world xyz per node (s.rest placed in the world)
    double[] wheels = s.roadWheel(mount, up, forward, radius, restLength, travel, spring, damper,
                                  inertia, gripAlong, gripAcross, massShare);  // per road wheel, back to back
    SoftApi.Body body = SoftApi.create(level, s, nodes, wheels, friction, velocity, aeroScale);

    // every server tick:
    body.terrain(level, tick);                                 // blocks around the body to the solver
    body.wheels(input);                                        // 11 values per road wheel, see below
    SoftApi.advance(level);                                    // let the level's simulation take its next step
    var snap = body.latest();                                  // newest finished step (nodes, wheels, tick)
    double[] frame = SoftApi.frame(s, snap.nodes(), lastQuat); // rigid fit: centre, rest centre, quaternion
}
```

Road wheel input, 11 doubles per wheel: steer (rad), drive torque (N·m), brake torque (N·m), strut lift (m; ride
height, hydraulics, a flat tyre), spin (rad/s) or NaN to keep, world forward xyz, world up xyz (or zeros to use the
wheel's pinned frame). A strut that changes length wakes a sleeping body.

Moving and pushing a body from outside:

- `body.move(offset)` — a teleport, shape and speed kept;
- `body.transform(pivot, quaternion, offset)` — a teleport that also turns the body (placing it on a tilted deck);
- `body.push(structure, point, impulse)` — an impulse (N·s) shared by the nearest nodes by mass (weight of a player
  standing on it, a hit);
- `body.velocity(v)` — set every node's speed;
- `body.remove()`, `body.dead()` — remove it / it was removed or the simulation restarted.

`SoftApi.stepNow(level)` steps synchronously on the calling thread (for tests that run many ticks in one game tick).

The native ABI is described in [NATIVE_ABI.md](NATIVE_ABI.md); the soft-body JNI entry points are in
`dev.physicalist.nativecore.NativeSoft`.

## Tests

`gradlew runGameTestServer` runs the soft-body game tests (`SoftBodyGameTests`): structure building, a car crumpling,
doors, a plane's engine, flight, a wall crash tearing it apart, and a wing that springs back from a blow but tears off
under a crash-size one. `cargo test --release softbody` runs the native tests.
