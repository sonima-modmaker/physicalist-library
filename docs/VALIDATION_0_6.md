# Physicalist 0.6.0 validation

Date: 2026-10-26. Platform: Windows. Minecraft: 1.21.1. Java target: 21.

## Server matrix

- NeoForge 21.1.219: 19 required GameTests passed.
- NeoForge 21.1.251: 19 required GameTests passed.
- Final playable artifact is built against the lower declared endpoint, 21.1.219.

The suite includes existing walking/support, assembly, friction and collision checks, plus spring convergence and momentum, damping/settling, static anchors, antipodal angular limits, break force, invalid geometry, coincident anchors, spatial-index parity at negative coordinates, large-query fallback, and ragdoll fall/save/reload/tool access.

## Standalone checks

Run `verification/run.ps1` after the default build and GameTest server have generated their classpath.

- Manual/automatic aerodynamics, wing balance, mass, fall drag and swept collision: passed.
- 20,000 randomized oriented contact and swept-contact comparisons against the reference: passed.
- Broadphase workload: 1,600 boxes and 2,000 local queries, warmup for both algorithms. Potential pairs: 3,200,000. Indexed candidates / actual intersections: 1,938. Results match brute force.
- One observed run: brute force 14.543 ms; indexed queries 0.814 ms; index construction separately 1.729 ms. These are synthetic CPU timings from this machine, not Minecraft TPS or a promise for another scene.

## Scope of verification

The client renderer compiles and client classes are isolated from dedicated-server registration. The graphical skin rendering, interpolation in a real multiplayer session and the user's modpack were not visually exercised in this run. The tests do not certify every external adapter or guarantee that dense unbounded piles remain at 20 TPS. Use the opt-in counters and real server MSPT to measure those workloads.

No gameplay JAR was replaced automatically in the Prism instance: the previously used instance currently has no active Physicalist JAR. The release is supplied as a separate build artifact.
