# Physicalist 0.6.2: ragdoll contact fix

The previous 0.6.1 workaround disabled internal contacts. This release restores limb-limb and limb-torso collision. Only small neck/shoulder/hip attachment caps are omitted for connected pairs. World contacts retain the full limb geometry.

Positional recovery is separated from kinetic velocity. Ragdoll socket velocity constraints alternate with zero-restitution self/world contact impulses, rather than treating depenetration distance as angular or linear velocity.

Regression coverage includes a deliberately overlapping folded-arm pose: arms must separate, all limbs must remain outside the torso beyond contact tolerance, angular speed must remain bounded, and falling bodies must settle and sleep. The added velocity-joint test verifies conservation of paired linear momentum and no increase of kinetic energy.

The server suite has 21 required tests on NeoForge 21.1.219 and 21.1.251. Exact run logs are saved under `build/reports/physicalist-0.6.2/`. The graphical client was not exercised in this run.
