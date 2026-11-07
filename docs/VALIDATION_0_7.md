# 0.7 validation record

Local Windows x86-64 validation, Java 21, Minecraft 1.21.1.

- Rust native tests: **5 passed** — falling/sleeping, stable stacks, an off-centre
  compound tipping at large coordinates, thin-wall CCD, and articulated root
  momentum/tool velocity.
- Physicalist GameTests: **25 required passed** on NeoForge **21.1.219** and
  **21.1.265**, with the Rapier backend explicitly loaded.
- Java fallback: **25 required passed** with
  `JAVA_TOOL_OPTIONS=-Dphysicalist.native.disable=true`. Three native-only tests
  return early in this mode; the other tests exercise the fallback and common API.
- Air War companion integration: **52 required passed** on the original
  development dependency set (NeoForge 21.1.219, Create 6.0.9, Sable 1.0.5).
- Air War companion integration: **52 required passed** with the Prism profile's
  Create **6.0.10**, Aeronautics **1.3.2**, Sable **2.0.6**, bundled Veil **4.3.2**
  and Ponder **1.0.82**, on NeoForge **21.1.265**. The playable profile uses
  NeoForge **21.1.262**; client startup is checked separately.
- Installed Physicalist **0.7.0** and Air War **5.01** into Prism's **1.21.1**
  profile with verified SHA-256 copies. Launched that profile on NeoForge
  **21.1.262**; both JARs were discovered and the client log confirmed
  `Rapier3D 0.36.1 / f64 / ABI 1`. The client completed shader loading and
  remained running at the menu. Existing user worlds were not opened by the agent.
- Standalone verification: manual/geometric aero, wing balance, mass, fall drag,
  swept contacts, and **20,000** randomized OBB/contact/sweep comparisons passed.
- The Java obstacle-index microbenchmark retained identical hits. This is a
  fallback/index benchmark, not a claim about whole-mod TPS or MCS performance.

The final tests include accumulating tiny quaternion rotations, invalid folded
ragdoll spawn recovery, self-contact, falling/sleeping, save/reload, actual wand
movement of a sleeping articulation, unsupported assembly tipping, removed-floor
invalidation, stacked contacts and walking support. The folded-pose test allows
two seconds to settle an intentionally intersecting pose while bounding every
transient angular speed; final penetration and spin limits are retained.

The off-centre GameTest clears the template's invisible boundary walls so the
assembly has room to tip. The remote Air War handoff test preloads its flight
corridor: it tests unloaded-player-area entity ticking and chunk handoff, not
asynchronous terrain-generation throughput at the GameTest server's accelerated
tick rate.

These tests do not establish a universal TPS gain, unlimited object counts,
perfect compatibility with every modpack, or visual equivalence to another mod.
The interactive game remains the place to assess feel, rendering and complex
ship interactions. Use `/physicalist engine` and `/physicalist stats` to record
the actual backend and scene cost in a reproducible world.
