# 0.7.1 / Air War 5.02 validation

- Physicalist: all **25** required server GameTests passed on NeoForge 21.1.219.
- Air War: all **53** required integration tests passed on NeoForge 21.1.265
  with Create 6.0.10, Aeronautics 1.3.2, Sable 2.0.6 and Veil 4.3.2.
- The new regression executes the actual `/ctaw_physics` command for FAB-1500,
  Kh-101, C-75, Vikhr, AIM-9X and SC-250. Each body is compared for 20 steps
  against the same model enqueued directly into Physicalist: position, velocity,
  orientation, native scene registration and library force ownership must match.
  Initial angular velocity exceeds the old wreck spin clamp.
- Corrected an additional callback bug: absent C-75 speed metadata defaulted to
  zero and could zero a directly enqueued body's velocity. That speed preservation
  now applies only to a powered C-75 with explicit metadata.
- Command-spawned and passive bodies bypass the legacy Air War force/movement
  stage. Powered flight continues to supply its own guidance/thrust forces.
- `/physicalist info` now reports the selected body's native ID, state and force
  owner; a loaded native DLL alone is not proof that a particular entity is active.

The standalone native binary is unchanged from the validated 0.7.0 engine.
See [0.7 validation](VALIDATION_0_7.md) for the five Rust tests and fallback checks.
