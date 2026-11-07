# Physical chains (Physicalist 0.9.0)

Take **Physical Chain Tool** from the Physicalist creative tab, or use
`/give @s physicalist_library:chain_tool`. Right click the first attachment point,
then the second. Shift-right-click in the air selects yourself. Shift-right-click a block cancels the selection. Right click an actual
link with vanilla shears to cut the adjoining joint. Cut links remain physical;
the chain is not deleted. The wand and deleter can select the chain as one tool
object while its links continue to simulate independently.

Anchors can be world blocks, players, mobs, items, vehicles and other entities.
Registered single rigid bodies attach directly to their native body. Ordinary entities
receive bounded velocity changes from the joint reaction, and players receive motion
updates. Two players can use the chain for tug-of-war; shearing ends the pull. Attachments
store local points and entity/ship UUIDs; cuts, link poses and velocities persist
with the chain entity. Endpoints in different dimensions are rejected. Length is
limited to 0.3-32 blocks and at most 65 physical links to bound per-chain cost.

On Windows the links share the Rapier scene, spherical impulse joints, terrain and
body collision. Only adjacent links disable mutual collision. A cut rebuilds the
chain's constraints without resetting link velocities. Ordinary Physicalist bodies
receive joint reactions directly. Optional Sable/Aeronautics attachments resolve
plot-local points through the sublevel pose and return joint tension as a queued
point force. Reactions are limited to 100 kN to bound a teleport-induced spike.
A Java XPBD fallback is also provided; native articulated accuracy and Sable tension
feedback require the native backend. An unloaded endpoint pauses its chain.

Developer entry points: `ChainEndpoint.world`, `ChainEndpoint.block`,
`ChainEndpoint.entity`, `PhysicalChainEntity.initialize`, `cutAt`, `links`, and
`NativeWorlds.enqueue(PhysicalChainEntity)`. The bridge is now ABI 3;
`jointForce0` reports force on joint body B in world N, while `ignore0` temporarily
excludes only colliders fully contained in a specified world AABB. Use the public
PhysicsBody adapter instead of JNI when integrating a new vehicle.

The ragdoll retains collision between body parts and articulated joint limits.
Strong angular motors were removed, a small shoulder/hip/neck clearance prevents
contact locking, and initial/changed limb angular velocities are mapped into native
joint coordinates. This is physical articulation, not an added animation.


## Walking and contact

Assembled blocks use the shared native scene. Their pose is synchronized as one
position/quaternion sample; the renderer does not interpolate independent Euler
angles. Vanilla bounding-box pushes between a player and registered bodies are
suppressed; intentional side pushes use actual model contacts. Walking columns
skip numerically empty cells and remain inside the model's vertical bounds.
Player contact proxies used for chains are scoped to that chain, so they cannot
push nearby standing platforms. A chain ignores contact with its own attached
player endpoints to avoid a permanently overlapping waist attachment.

## 0.9.1 contact and towing update

Chains retain a fixed maximum length and never break automatically. Native rigid-body endpoints additionally use a hard rope constraint. Sable ship/world endpoints use the Sable constraint pipeline, rather than relying on forces from lightweight links alone. Sable queued forces receive impulses converted from Newtons over the 0.05-second game tick; unloaded bodies without valid mass are ignored until ready.

Client links now buffer timestamped position/quaternion snapshots with two ticks of interpolation delay instead of applying two separate easing steps. Thin chain links no longer create solid walking columns: native player proxies move the links aside without trapping the player. Ordinary live entities with a nonzero bounding box participate in external contact feedback; velocity changes are bounded. Mods that override their entities' motion may need a custom integration.
