# Physicalist 0.8.1: walking and cursor capture

The native scene previously represented players as moving, infinite-mass collision
proxies while Minecraft's movement solver also resolved players against physical
surfaces. Player gravity and penetration correction could therefore drive the
supporting body. Player walking now remains in the character movement path;
players are excluded from native obstacle proxies. Deliberate side pushing is
handled by `PhysicalistWalkingCollision.pushBlocked`, with support contacts filtered.
This is one-way standing support, not added player weight or a welded body freeze.

The supplied QuistaPhys `PhysMoveCollisionMixin` / `PhysCollision` were inspected
statically: they resolve the moving character against physical shapes and reset
its fall/ground state. No source was copied. Full behaviour parity is not claimed.

Generated walking columns now have a stable world grid and cannot exceed the actual
oriented box's global vertical bounds. This prevents exaggerated surface heights
from dividing a footprint allowance by a near-horizontal face-normal component.

`GrabController.velocity(error,current,gravity)` controls bounded target velocity,
instead of repeatedly accumulating capped impulses. Desired speed is at most
1.25 blocks/tick, with damping and gravity compensation. A captured body with an
already extreme speed is brought under the same velocity limit. The built-in wand,
ragdoll adapter and Air War 5.12 library-wand adapter use this controller.

31 required library GameTests passed on NeoForge 21.1.219. New checks register a
real player entity over a settled native support and verify 60 walking steps
without kicking the body or losing the surface. Another test starts a grabbed body
at extreme velocity, abruptly changes the cursor, checks speed on every step and
requires settling at the final cursor position. These are automated tests, not a
visual verification in the user's existing world.

Air War 5.12 with Physicalist 0.8.1 passed all 60 required integration GameTests
on NeoForge 21.1.265 with the matching Sable/Create/Aeronautics/Veil development
dependencies. The new ordnance adapter test captures a fast-moving C-75 and
repeatedly reverses the cursor direction; velocity stays below 1.3 blocks/tick.
Both playable JARs passed ZIP integrity verification. Installation in the user's
profile is pending Minecraft closure at this stage.
