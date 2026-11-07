# Changelog

## 0.16.4 — Soft bodies: wings that never stay bent, experimental switch

- Soft bodies are now an **experimental feature, off by default**: `experimental.softBodies` in
  `physicalist_library-common.toml`. Off, the Physicalist tool is hidden from the creative tab and the server refuses
  soft-body uploads. `SoftApi` stays available to other mods.
- Aircraft wings and tailplanes are purely elastic: they flex under load and always spring back. Their root is
  brittle — past 8 % strain it snaps and the whole wing tears off (a crash), with no state in between where a wing
  keeps a bend. Native beams with a negative strain limit are brittle.
- More thrust: propeller engines get 380 W/kg, a coarser pitch and a higher rev limit (the trainer: 6.5 kN static,
  about 4 kN at 45 m/s, was 4.5 / 2.9 kN), so the power reaches the air in flight.
- Surfaces shut in by a model's own skin (cabins, seats, double walls) get no aerodynamics; an aircraft's fuselage skin
  gets 40 % of a flat plate's force. Imported models no longer drag like bricks or nose over.
- Wheels are drawn rolling over the ground.
- A strut changing length (ride height, hydraulics, a flat tyre) wakes a sleeping body; the whole wheel well around
  a road wheel's strut is stiff, so everyday driving no longer pushes the arch up into the body.
- New guide: [docs/SOFT_BODIES.md](docs/SOFT_BODIES.md).

## 0.16.1 — Soft-body API for other mods

- `dev.physicalist.soft.SoftApi`: build a structure from any model, put it in the world with native road wheels
  (strut, spring, damper, friction-ellipse tyre, drive, brake, steering), hand it terrain every tick, read back node
  positions, wheel states and a rigid frame. `move`, `transform` (teleport with rotation), `push` (impulse at a point),
  `velocity`.

## 0.16.0 — A flyable soft-body plane

- Built-in trainer plane: blade-element propeller with slipstream, ailerons/elevator/rudder deflecting their patches,
  rolling wheels, a seat (spawning seats the player), keyboard controls.
- Soft bodies simulate on their own thread per dimension (commands in, snapshots out); clients play poses back from a
  timeline for smooth motion. F3+B draws nodes, beams, aero normals and the rotor disc.

## 0.15.0 — Soft bodies

- Native soft-body solver: surface-sampled nodes, beams with plastic deformation and breaking, mass-weighted shape
  matching per part, hinged doors with latches, aerodynamic patches. Models load from GLB/glTF, OBJ, FBX, Minecraft/
  Blockbench JSON.

## 0.14.0 — Faster chains

- Native step: 4 collision steps × 8 solver substeps per tick (was 8 × 12); chains about 3× faster.

## 0.12.1 — No smoke boxes at a full budget

- When the section budget is spent, smoke thins out over the last five cells before the grid's edge (its puffs carry on beyond), so a big fire no longer leaves solid 16-block boxes of smoke. Grid smoke density is capped at 2.5.

## 0.12.0 — Clouds from smoke

- Smoke from a strong fire keeps climbing after leaving the fire's hot gas (its buoyancy fades over about a minute) and lives up to three minutes, so a big fire's plume reaches the cloud layer and turns into clouds.
- New coarse cloud volume (`SmokeSimulation.farFocus` / `far()`, native `far_volume`): cloud puffs and smoke drifting beyond the grid, splatted into 4-block cells around the camera four times a second, for volumetric cloud rendering.
- Smoke dissipates half as fast by default (0.006/s).
- The edge of the active grid is an open outflow: when the section budget (now 512) ran out, smoke trapped in a section that could not grow piled up into a solid black box. Sections holding only invisible haze (under 0.01) are released sooner.
- Smoke stays in a sealed room: advection no longer mixes the zero of wall cells into the air beside them (which drained smoke along every wall), and smoke in a pocket sealed on all sides dissipates ten times slower. A closed stone room keeps 94% of its smoke over a minute (was 45%).
- A blast's smoke is a single burst: its puffs do not get a fire plume's lasting lift, so an explosion no longer leaves a column climbing for minutes.
- Fluid ABI 2.

## 0.11.0 — Smoke, heat and clouds

- New native smoke and heat solver (`native/src/fluid.rs`, Java `dev.physicalist.nativecore.SmokeSimulation`). Air is simulated in one-block cells grouped by chunk section, only where there is smoke, heat or motion. Velocities sit on cell faces (MAC grid) and are projected divergence free against solid blocks, so hot smoke rises at a few blocks per second, spreads under ceilings, fills a closed room and pours out of an opened door. Visible smoke is a set of puffs carried by the flow; puffs reaching cloud height become long-lived cloud puffs drifting with the wind.
- The simulation steps on its own daemon thread. The game thread only queues sources and answers which blocks stop gas; a step that is not finished is skipped and its time carried over, so slow hardware gets slower smoke, never a slower game.
- Chains: a player hanging on a chain now hangs still and can swing. The tether replaced the player's motion with an upward correction every tick (up to 0.5 blocks/tick), so the holder hopped and the chain thrashed; it now removes only the stretching speed, adds the gravity about to pull along the chain, and keeps the rest of the player's motion.
- Rigid-body physics is unchanged: the native benchmark checksum stays `cec968c74feafdc3`.
## 0.10.0 — Faster physics, same results

- Settled bodies now sleep. Entities keep orientation in a float quaternion, and reading back the native double pose shifted it by ~1e-7, which the sync compared at 1e-7: every tick a few resting bodies looked teleported and were woken together with their whole contact island, so a settled pile never slept. Rotation is now compared at float precision (1e-6); position stays at 1e-7. A pile of 125 blocks: 14.4 ms/tick -> 1.1 ms/tick.
- x86-64-v3 (AVX2/FMA/BMI) build of the native backend, chosen at start-up by a tiny dependency-free CPU probe library; other processors keep the baseline build. Both give bit-identical results (same checksum in the native benchmark); the v3 build steps Rapier ~1.5x faster. `-Dphysicalist.native.baseline=true` forces the baseline build.
- Contacts are folded into the per-tick record directly each substep instead of through a temporary snapshot (no per-body allocation or lookup); same order and results. Full LTO for the native library.
- Heavy scene (129 blocks, 4 chains, 3 ragdolls): 20.9 ms -> 4.1 ms per tick on x86-64-v3, 6.4 ms on the baseline build. Measured by `BenchmarkGameTests` (`gradlew runGameTestServer -Pphysicalist_bench`) and the native `bench_step` test.


## 0.9.6 � Chains stay on their holder

- Drawn chains pin attached ends to where the holder is drawn this frame (players, mobs, physics bodies and now Sable ships through the client's interpolated ship pose) and relax the links like a rope: only stretched links pull their neighbours. The old shift moved the whole chain proportionally, so it slid along with a walking player, and switched off past 4 blocks, so the chain let go during takeoff.


## 0.9.5 � Never frozen off-screen

- Physics bodies are marked `noCulling` on clients (own entities at construction; bodies from other mods through a new `no_cull` packet when the server first tracks them and when a player starts tracking them). Entity Culling's tick culling froze any entity that left the screen for a frame, so a body held with the wand and lifted fast stayed frozen out of view until the player looked back at the stale spot, then teleported.


## 0.9.4 � Tight grab, leash chains

- Physics wand follows the cursor up to 4 blocks/tick (was 1.25) and closes 70% of the gap per tick; held bodies no longer fall behind a fast camera turn and drop out of view.
- Fast-moving entities are culled by their swept box (last to current tick), so they no longer blink out while drawn between the two positions.
- Chains on players and mobs act as a leash: no drag while slack; once taut an inelastic impulse removes only the stretching speed, shared by mass. Replaces per-tick solver forces that slowed players and made both ends bounce back and forth.
- Chain ends are drawn pinned to their attachment every frame instead of trailing two buffered ticks behind.
- The rope limit for chains on physical bodies is 1.5% shorter than the links, so the two constraints no longer fight and shake under tension.


## 0.9.3 � Buoyancy

- Native Archimedes buoyancy: each collision box is sampled against the water surface; lift acts at the submerged centroid, so bodies self-right and ragdoll limbs float individually. Water drag on movement and rotation.
- Physical blocks take their density from the material: wool 0.3, wood 0.6, ice 0.92, glass 2.5, stone 2.4, metal 7.8 (relative to water).
- New config section `water`: enabled, buoyancyMultiplier, floatEverything (+ floatEverythingDensity), ragdollDensity (0.85, floats), chainDensity, defaultDensity, linearDrag, angularDrag. `PhysicsBody.waterDensity()` lets other mods set their own.
- Native ABI 4 (`water0`).


## 0.9.2 � Calm chains

- Lay new chain links on a sagging curve whose arc length matches the chain; the old straight layout was 8% too long, so the chain started compressed and buckled into endless swinging and spinning.
- Add native joint friction for chain joints (Rapier 0.36 leaves coupled angular motors unimplemented). A chain between two fixed points now comes to rest and sleeps.
- Stronger damping for chain links; nearby links of one chain no longer collide with each other.
- Drive kinematic chain anchors by velocity toward moving attachments instead of teleporting them every tick.
- Fix chains disappearing on the client: move packets shrank the culling box to one 0.2-block link, and the render distance was about 13 blocks. Culling now covers all links.
- Alternate visual link rotation by 90 degrees so the chain reads as interlocking links.


## 0.8.1

- Keep players out of native kinematic obstacle proxies; resolve player walking against physical surfaces without driving the supporting body with player gravity. Side pushing remains in the walking controller.
- Do not interpret standing/step contacts as side pushes. Bound generated walking columns to actual model height and stabilize their grid.
- Add `GrabController` with bounded target velocity; use it for the wand and ragdoll grab adapter. Air War 5.12 uses the same controller for ordnance.
- Add live registered-player/native-support and abrupt-cursor/high-speed capture regressions.


## 0.8.0 � Surface aerodynamics and continuous pose

- Add authored `AeroSurface` definitions and SI force/torque evaluation at the local flow (`v + omega cross r`), including stall, induced drag and wind. Thin collision plates remain an automatic fallback.
- Apply forces through native mass/inertia instead of directly correcting Euler angles. Bound aerodynamic work to avoid numerical acceleration of lightweight spinning plates; external propulsion remains separate.
- Add `PropellerForce` for thrust, off-centre moments and counter-rotating reaction torque.
- Add direct quaternion adapter hooks. Air War 5.10 publishes atomic pose samples, interpolates quaternions, bounds missing-packet extrapolation and removes decorative spin from physical Vikhr rendering.
- Bump the independent native bridge to ABI 2. No MCS/FPV code, assets or binaries are included. Their content configuration was inspected for architectural comparison only.


## 0.7.1

- Add per-entity native scene diagnostics to `/physicalist info`: native body ID, active/sleeping/terrain-wait state and force ownership.
- Expose `NativeWorlds.simulates(entity)` and `bodyReport(entity)` so companion mods and tests can verify a specific entity rather than just native library availability.
- Air War 5.02 sends loose/inert ordnance directly through the Physicalist body path, bypassing its legacy flight forces and motion clamps. Powered guidance retains its own controller forces.

## 0.7.0 — Major physics update

- Add an independent Rust/JNI backend based on Rapier3D 0.36.1 (f64), bundled for Windows x86-64; retain the Java fallback.
- Simulate built-in block assemblies and six-body player ragdolls in persistent per-dimension scenes with shared contacts, friction, inertia, sleeping and CCD.
- Cache loaded terrain sections, merge full block prisms, invalidate changed sections and expire unused geometry.
- Add native joints, limits, damping and moving-world contact proxies with reaction impulses; provide optional reflective Sable support.
- Preserve tiny quaternion rotations when applying native poses. Resolve overlapping ragdoll spawn poses without injecting velocity.
- Add deferred `NativeWorlds.enqueue(PhysicsBody)`, collider revisions and `/physicalist engine` diagnostics while keeping the synchronous Java API compatible.
- Add native Rust and Minecraft integration regressions, source build instructions, dependency license notices and English/Russian migration documentation.
- No MCS/QuistaPhys source, binaries or assets are included. This update changes the physics architecture, not the entire combat simulator.

## 0.6.3

- Accept NeoForge 21.1.219 and newer 21.1.x patches for Minecraft 1.21.1 instead of rejecting 21.1.252 and above.
- Add NeoForge 21.1.265 to the build and GameTest CI matrix; keep 21.1.219 as the compilation baseline.
- Other Minecraft versions remain outside the supported range.

## 0.6.2

- Restore limb-limb and limb-torso contact; omit only small connected attachment caps.
- Split positional collision/joint recovery from physical velocities to prevent overlap-generated spin.
- Add velocity-level socket constraints and alternate them with zero-restitution contacts for stable support.
- Test folded-limb separation, torso exclusion, bounded angular motion, settling and contact momentum/energy.

## 0.6.1

- Disable all collisions between limbs of the same ragdoll; preserve joints and world contacts.
- Remove the artificial spawn spin and forward shove; inherit only player velocity.
- Add a folded, overlapping-arm regression that checks for self-repulsion and angular-energy injection.

## 0.6.0

- Add independent mass-aware rigid states with box inertia tensors, point impulses and quaternion poses.
- Add XPBD elastic distance joints and ball sockets with damping, swing cones and break thresholds.
- Add a reusable joint-island driver and force/impulse/blast helpers for existing entity adapters.
- Add an operator command to spawn a six-part physical player-skin test dummy: `/physicalist ragdoll [online player]`.
- Persist limb poses, velocities, owner skin properties and loaded lifetime. Interpolate limb snapshots on clients.
- Integrate ragdolls with the wand, rotation controls, deleter, body selection and green collision debug view.
- Add a per-step spatial obstacle index with bounded large-object/query fallbacks and negative-coordinate support.
- Stop simulation after impact callbacks remove an entity; rebuild collision caches after callbacks.
- Preserve active body registrations across dimensions and clear server-scoped tracking/counters on shutdown.
- Add opt-in solver timing and broadphase/contact counters under `/physicalist stats`.
- Add English and Russian elasticity/integration guides with units, examples and explicit limitations.
- Keep the `physicalist_library` ID and existing adapter interface compatibility.

Tests cover the existing walking/contact/assembly suite and new spring convergence, momentum, angular limits, breakage, invalid geometry, spatial-index parity, ragdoll falling, persistence and tool discovery.
