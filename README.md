# Physicalist

**Website:** https://sonima-modmaker.github.io/physicalist-library/

Physicalist is a standalone physics mod and API for **Minecraft 1.21.1** on
**NeoForge 21.1.219–21.1.251**. It provides compound collision, collision-driven
aerodynamics, hinges, and a server-side rigid-body step for other mods. Version
The mod also includes creative-mode tools for trying the physics
without writing an adapter first.

Installing this JAR alone does **not** replace the physics of every Minecraft
entity. A vehicle mod must pass its model geometry and motion through the API.

## Start here

| Goal | Guide |
|---|---|
| Install the mod or build from source | [Installation](docs/INSTALL.md) |
| Integrate vehicles or projectiles | [API and example adapter](docs/API.md) |
| Tune lift, drag, contact and hinges | [Configuration reference](docs/CONFIG.md) |
| Diagnose loading and physics problems | [Troubleshooting](docs/TROUBLESHOOTING.md) |
| Read the previous Russian documentation | [Russian documentation (older version)](docs/ru/INSTALL_RU.md) |

The built mod is `build/libs/physicalist_library-0.5.1.jar`. The `-sources.jar`
file is for development and must not be installed as the playable mod.

## What's included

| Component | Purpose |
|---|---|
| `CompoundCollision` | Contacts between oriented boxes and swept thin-part collision |
| `AutoAerodynamics` | Lift, fall drag and torque inferred from thin collision volumes |
| `Aerodynamics` | Manual lift/drag model for guided or specialized vehicles |
| `PhysicalistSimulation` | One server-side step for a compound physical body |
| `PhysicsBody` | Vehicle adapter interface: shape, pose, mass and angular velocity |
| `Hinge` / `ContactHinge` | Joint motion and contact-driven folding |
| `PhysicalistBodies` | Registry of currently simulated bodies for tools and commands |
| `PhysicalBlockEntity` | One movable rigid body assembled from selected world blocks and their voxel collision shapes; supported bodies settle and sleep |
| `PhysicalistExternalCollisions` | Optional moving-world collision providers, including Sable integration supplied by Create: The Air War |

The creative tab **Physicalist** contains a **Physics Wand**, **Block
Assembler**, and **Physical Entity Deleter**. Hold right click with the wand
while aiming at a physical entity to drag it. Scroll to move the held body
closer or farther away; hold Tab and move the mouse to rotate it without
turning the camera. With the assembler, right click
two opposite corners to turn the selected blocks into **one** physical entity;
sneak-right-click clears the first corner. The assembled body retains each
block's voxel collision, rotates from off-center impacts, and can be pushed by
other entities. F3+B draws its collision parts as green outlines. Aim the
deleter and right click to remove a physical entity. These are creative-mode
tools. A selection is limited to 128 blocks, a 16-block span per axis, and
512 voxel collision boxes. Blocks with block entities or fluid states are
rejected so their data is not lost.

Operators can use `/physicalist list`, `/physicalist select <id>` or
`/physicalist select look`, `/physicalist info`, `/physicalist scale <factor>`,
`/physicalist delete`, `/physicalist block <x> <y> <z>`,
`/physicalist assemble <x1> <y1> <z1> <x2> <y2> <z2>`, and
`/physicalist spawn <block_id> <x> <y> <z>`. The `block` command converts an
existing world block, `assemble` creates one rigid body from a selected region,
and `spawn` creates a physical copy without removing one.
Scaling is available for entities that implement `PhysicalistScalable`. The
library's physical block does; other mods can opt in explicitly so that both
their renderer and collision geometry scale together.

The wand can grab every live body stepped through `PhysicalistSimulation`.
Mods using individual math classes without that simulation can expose their
bodies through `PhysicalistBodyProvider`. This distinction avoids claiming
control over unrelated entities or silently modifying their flight controller.

The server solver checks the swept route once per tick and skips collision
substeps when the complete route is clear. Near blocks it retains swept
thin-part checks; a block destroyed by an impact invalidates the cached route.
The oriented-box contact math also avoids most temporary allocations.

## Vehicle integrations

Create: The Air War uses the library's collision, aerodynamics and hinge math.
Its current library-compatible build also exposes active rockets and debris to the
Physicalist Wand, Deleter and `/physicalist` commands through a body adapter.
The wand applies impulses through Create: The Air War's flight solver, and
F3+B remains drawn by that mod so collision outlines are not duplicated.
Its Sable bridge supplies the library's assembled blocks with moving-ship
collision and an opposing force on the ship. Install matching current builds
of both mods for this behavior.
It still owns weapon guidance, engines, explosions, debris, chunk loading and
its own flight adapter. Its current weapons use the manual aerodynamic path;
the new geometric auto-aero mode is available to vehicle adapters that call
`PhysicalistSimulation` or `AutoAerodynamics.step`.

**Immersive Vehicles Refurbished** is a planned integration target. There is
no IVR adapter or runtime dependency in this repository yet, so installing
Physicalist does not alter its vehicles. The projects are independent.

## Boundaries

- The generic simulation handles block collisions and optional moving-world
  providers. The built-in block assembly also responds to nearby entities;
  vehicle adapters still own their own passenger behavior.
- Swept collision is continuous for translation; fast rotation still needs
  substeps.
- The library does not issue chunk tickets or synchronize arbitrary adapter
  state. The vehicle mod owns persistence and networking for its own bodies.
- An impact callback reports the contact; explosions, damage and sounds belong
  to the vehicle mod.

## Verification and license

The project builds on both supported NeoForge endpoints. The standalone
`verification/LibraryPhysicsTest.java` checks manual and geometric aero,
asymmetric wings, mass, fall drag and thin-part collision.
`verification/CollisionParityTest.java` compares 20,000 randomized oriented
contacts and swept contacts against the previous solver. Seven server GameTests
check persistence, assembly, collision-part selection, settling and waking,
the clear-path shortcut and a fast impact on thin bars.
Runtime TPS should still be measured in a real world before a production modpack.

Source code is **GPL-3.0-only**. `LICENSE` and `NOTICE` are included in the
project and built JAR. The project began from the
[NeoForge MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-NeoGradle).
