# Physicalist 0.8 validation

Local validation completed on 2026-10-30, Windows x86-64 / Java 21.

| Check | Result |
|---|---|
| Rust native tests | 8 passed |
| Library GameTests, NeoForge 21.1.219 | All 29 required passed |
| Library GameTests, NeoForge 21.1.265 | All 29 required passed |
| Air War 5.10 + Physicalist 0.8.0, NeoForge 21.1.265 | All 57 required passed |
| Final Physicalist artifact | Compiled against baseline 21.1.219 |
| Native backend in server tests | Rapier3D 0.36.1, f64, ABI 2 |

New regressions cover zero generated spin for stationary vertical ordnance, repeated
quaternion round trips through vertical orientations, antipodal quaternion signs,
bounded extrapolation, missing/invalid samples, point-flow surface forces, induced
moments, stall, counter-rotating rotors, force/torque impulse units, clearing tick loads
and energy-stable drag on lightweight plates. Existing contacts, assemblies, walking,
hand attack, ragdoll, native command equivalence and remote chunk tests remain included.

The remote-fragment test now checks crossing the actual destination chunk boundary,
rather than requiring an additional fixed 16 blocks of travel. The previous condition
incorrectly rejected a fragment which crossed the boundary but decelerated after
7 blocks under the revised drag. The failure's recorded removal reason was later
UNLOADED_TO_CHUNK after settling, not invalid native coordinates.

An intermediate Air War run read the development dependency JAR while a library
build was rewriting it; it failed with ZipException/ClassNotFoundException. The
final run used a completed, unchanged JAR and passed. Never rebuild a dependency
artifact concurrently with a process loading that artifact.

These automated checks do not prove every modpack interaction or the subjective
quality of flight animation. The user's Minecraft client was still running the old
0.7.1/5.02 pair at this point; installation and a visual test require restarting it.
The supplied FPV pack was inspected statically; no FPV gameplay flight was claimed.
