# Physicalist Library

Physicalist Library is a standalone physics mod and API for **Minecraft 1.21.1** on
**NeoForge 21.1.219–21.1.251**. It provides compound collision, collision-driven
aerodynamics, hinges, and a server-side rigid-body step for other mods. Version
**0.3.0** also includes a small set of creative-mode tools for trying the physics
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

The built mod is `build/libs/physicalist_library-0.3.0.jar`. The `-sources.jar`
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
| `PhysicalBlockEntity` | A movable copy of one ordinary block using its captured voxel collision shape |

The creative tab **Physicalist Library** contains a **Physics Wand**, **Block
Assembler**, and **Physical Entity Deleter**. Hold right click with the wand
while aiming at a physical entity to drag it. Right click an ordinary block
with the assembler to replace it with a physical block entity. Aim the deleter
and right click to remove a physical entity. These are creative-mode tools.
Blocks with block entities, empty collision/selection shapes, or more than 64
voxel boxes cannot be assembled; their data would not be preserved safely.

Operators can use `/physicalist list`, `/physicalist select <id>` or
`/physicalist select look`, `/physicalist info`, `/physicalist scale <factor>`,
`/physicalist delete`, `/physicalist block <x> <y> <z>`, and
`/physicalist spawn <block_id> <x> <y> <z>`. The `block` command converts an
existing world block; `spawn` creates a physical copy without removing one.
Scaling is available for entities that implement `PhysicalistScalable`. The
library's physical block does; other mods can opt in explicitly so that both
their renderer and collision geometry scale together.

The wand can grab every live body stepped through `PhysicalistSimulation`.
Mods using individual math classes without that simulation can expose their
bodies through `PhysicalistBodyProvider`. This distinction avoids claiming
control over unrelated entities or silently modifying their flight controller.

## Vehicle integrations

Create: The Air War uses the library's collision, aerodynamics and hinge math.
It still owns weapon guidance, engines, explosions, debris, chunk loading and
its own flight adapter. Its current weapons use the manual aerodynamic path;
the new geometric auto-aero mode is available to vehicle adapters that call
`PhysicalistSimulation` or `AutoAerodynamics.step`.

**Immersive Vehicles Refurbished** is a planned integration target. There is
no IVR adapter or runtime dependency in this repository yet, so installing
Physicalist Library does not alter its vehicles. The projects are independent.

## Boundaries

- The generic simulation handles block collisions. Another mod must integrate
  entity-to-entity contacts, passengers and moving external structures.
- Swept collision is continuous for translation; fast rotation still needs
  substeps.
- The library does not issue chunk tickets or synchronize arbitrary adapter
  state. The vehicle mod owns persistence and networking for its own bodies.
- An impact callback reports the contact; explosions, damage and sounds belong
  to the vehicle mod.

## Verification and license

The project builds on both supported NeoForge endpoints. The standalone
`verification/LibraryPhysicsTest.java` checks manual and geometric aero,
asymmetric wings, mass, fall drag and thin-part collision. Runtime behavior
should also be checked in a real game instance before a production modpack.

Source code is **GPL-3.0-only**. `LICENSE` and `NOTICE` are included in the
project and built JAR. The project began from the
[NeoForge MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-NeoGradle).
