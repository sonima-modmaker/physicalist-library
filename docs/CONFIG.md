# Configuration reference

NeoForge writes `config/physicalist_library-common.toml` after the first
launch. Edit it while the game/server is stopped. Distances are in blocks;
speeds are blocks per game tick; coefficients are game-scale values rather
than SI quantities. Another mod may override the defaults with its own
`PhysicsProfile` or `AutoAeroProfile`.

## Motion

| Key | Default | Valid range | Effect |
|---|---:|---:|---|
| `motion.gravity` | `0.045` | `0…2` | Downward acceleration per tick |
| `motion.angularDamping` | `0.965` | `0…1` | Fraction of angular velocity retained each tick |
| `motion.maximumSpeed` | `16` | `0.001…4096` | Generic solver's speed cap |

Setting gravity in TOML cannot affect an entity whose mod never calls a
Physicalist motion method. The vehicle mod must feed its velocity into
the server-side step.

## Manual aerodynamics

These settings affect `Aerodynamics.step` and generic bodies whose
`automaticAerodynamics()` returns `false`.

| Key (prefix `aerodynamics.`) | Default | Range | Effect |
|---|---:|---:|---|
| `linearDrag` | `.002` | `0…1` | Baseline slowdown |
| `speedDrag` | `.0006` | `0…1` | More slowdown at higher speed |
| `crossflowDrag` | `.014` | `0…1` | Penalty for sideways flight |
| `damageDrag` | `.008` | `0…1` | Extra slowdown for damaged bodies |
| `liftFactor` | `.003` | `0…1` | Wing-area lift growth with speed squared |
| `maximumLift` | `.038` | `0…2` | Lift cap before wing-area multiplier |
| `restoringTorque` | `.0014` | `0…1` | Turn body toward its motion direction |
| `maximumRestoringTorque` | `.010` | `0…1` | Cap on that turn |
| `horizonTorque` | `.0005` | `0…1` | Level symmetric intact wings |
| `maximumHorizonTorque` | `.003` | `0…1` | Cap on leveling |
| `imbalanceTorque` | `.0015` | `0…1` | Roll from left/right wing difference |
| `maximumImbalanceTorque` | `.009` | `0…1` | Cap on imbalance roll |

Manual lift also depends on the adapter's `wingArea`, `imbalance`,
`stabilizers`, `forward`, `up` and damaged flag. A value of zero for
`wingArea` means no manual lift. The manual model caps base drag to 10%
per step to avoid an abrupt stop.

## Automatic aerodynamics

These settings affect `AutoAerodynamics.step`, including the default mode
of `PhysicalistSimulation`. The current oriented collision boxes are the
wing geometry. No second wing-area list is required.

| Key (prefix `autoAero.`) | Default | Range | Effect |
|---|---:|---:|---|
| `maximumThicknessRatio` | `.18` | `.001…1` | Maximum thickness relative to the shorter plate side |
| `minimumArea` | `.2` | `0…10000` | Minimum plate area in square blocks |
| `minimumAspectRatio` | `1.2` | `1…100` | Minimum span across the vehicle versus chord along it |
| `incidenceDegrees` | `5` | `−30…30` | Wing incidence; permits lift during level acceleration |
| `liftCoefficient` | `.012` | `0…10` | Lift per area, airflow and speed |
| `maximumLift` | `.24` | `0…10` | Total lift acceleration cap across all detected plates |
| `plateDragCoefficient` | `.008` | `0…10` | Drag when a plate faces the airflow, including a fall |
| `maximumPlateDrag` | `.2` | `0…10` | Total plate-drag acceleration cap |
| `torqueCoefficient` | `.15` | `0…10` | Rotational response to off-center air forces |
| `maximumTorque` | `.03` | `0…10` | Cap on aerodynamic angular acceleration |

`PhysicsBody.aerodynamicMass()` is `1` unless overridden. Lift and plate
drag are divided by that mass. For a heavy car, the same collision wings
require more speed or area to take off. A folded wing must have a changed
OBB orientation or positive thickness that no longer passes wing detection;
merely changing a visual animation cannot change the physics.

## Collision solver

| Key (prefix `collision.`) | Default | Range | Effect |
|---|---:|---:|---|
| `restitution` | `.12` | `0…1` | Fraction of normal speed returned after impact |
| `friction` | `.35` | `0…1` | Tangential speed removed during contact |
| `contactSlop` | `.001` | `0…1` | Small separation added after penetration resolution |
| `minimumImpactSpeed` | `.35` | `0…100` | Threshold for `PhysicsBody.onImpact` |
| `substepDistance` | `.08` | `.001…16` | Target motion distance between collision checks |
| `maximumSubsteps` | `320` | `1…512` | Hard cap on collision substeps per tick |

The generic solver uses a swept check for translating thin parts, but
fast rotation depends on substep count. Reducing `substepDistance` improves
accuracy and costs more server time. An adapter may have its own solver;
check its documentation before assuming these values affect it.

## Hinges

| Key (prefix `hinges.`) | Default | Range | Effect |
|---|---:|---:|---|
| `contactResistance` | `.009` | `0…1` | Load required to start contact-driven folding |
| `maximumTravelPerTick` | `.025` | `0…1` | Maximum hinge travel fraction per tick |
| `impactGain` | `1.1` | `0…100` | Converts impact speed to hinge motion |
| `returnRate` | `.006` | `0…1` | Hinge return rate |
| `latchRate` | `.055` | `0…1` | Latching motion rate |

The hinge API only computes motion. A vehicle mod must actually update
the associated model and collision boxes and save/synchronize the joint.

## Performance tuning

Large numbers of bodies, collision boxes, nearby blocks and substeps can
reduce TPS. Start by checking for stuck bodies. Then lower
`maximumSubsteps` slightly or raise `substepDistance` slightly, and verify
that fast thin parts still collide. Do not set the step distance to an
entire block for a thin wing. For an assembled block, the library captures
at most 64 voxel boxes to bound this cost.
