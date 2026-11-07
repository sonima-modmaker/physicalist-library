# Physicalist 0.9.0 validation

Local Windows x86-64 / Java 21 validation on 2026-10-30:

- 13 native Rust tests passed, including child angular state, a complete ragdoll,
  weight-correct anchor reactions, launcher-only exclusion and player proxy layers.
- All 39 required library GameTests passed on NeoForge 21.1.219.
- All 64 Air War integration GameTests passed on NeoForge 21.1.265 with Create
  6.0.10, Aeronautics 1.3.2, Sable 2.0.6 and Veil 4.3.2.
- Both playable JARs are built. The native bridge is ABI 3.

Checks include real assembled-platform walking and per-entity native registration,
vanilla push suppression, vertical quaternion round trips, independent ragdoll
motion, articulated chains, shears, cuts/save/reload, dynamic-body suspension,
player tug-of-war, ordinary entity attachments, body/chain contact, Sable anchor
movement and queued force feedback, actual server attack-packet dispatch, and
Vikhr launcher clearance. The platform walking fixture also runs with Sable loaded.

Earlier Java fallback tests exposed a ragdoll sleeping regression; grounded angular
friction was increased without changing free-air native articulation. The final
fallback result is recorded in the accompanying test log. These automated tests
are not a visual playtest inside the user's existing world.

Final Java fallback run: all 39 required tests passed (native-only cases explicitly skip).
