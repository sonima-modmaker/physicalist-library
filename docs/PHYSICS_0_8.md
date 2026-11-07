# Physicalist 0.8: surface forces and continuous orientation

This is an independently implemented extension of the shared Rapier3D scene.
It is not an MCS/QuistaPhys port. Install Physicalist 0.8.0 together with Air War
5.10 when testing the new ordnance adapter. Do not mix the ABI 2 Java bridge and
an ABI 1 DLL. Minecraft support remains 1.21.1, NeoForge 21.1.219+ within 21.1.x.

## What changes physically

Each surface receives the airflow at its own point, including rotational motion:
`pointVelocity = centreVelocity + angularVelocity cross arm - wind`.
Lift is perpendicular to flow; drag opposes flow. The model includes bounded
lift, smooth stall blending, induced drag and broadside drag. Applying the force
away from the centre of mass produces a moment. Native compound mass/inertia
then determines acceleration; no artificial horizon or Euler angle correction is
used by this surface model. A vertically supported stationary body has no airflow
and therefore no generated aerodynamic roll. Unsupported bodies can still tip.

Forces are calculated once per Minecraft tick; contacts use eight native substeps.
An energy-based bound prevents a large discrete drag/moment from overshooting on
light, thin parts. The bound uses the native inverse inertia, not a scalar angular
clamp. External engine loads are separate because an engine can add energy.
Collision-derived surfaces retain the configurable game-sized acceleration bound.
This is a real-time game approximation, not CFD or an engineering flight model.

## Adapter API

`PhysicsBody.aerodynamicSurfaces()` may return explicit local surfaces. When empty,
automatic aerodynamics infers surfaces from eligible thin collision boxes.
Use one block as one metre. Java entity velocity and `angularVelocity()` remain
blocks/tick and radians/tick; surface calculations convert them to SI internally.
Surface points are local offsets from the entity origin, not from the mass centre.
`centerOfMass()` must agree with the compound collider's volume distribution.
`nativeOrientation()` maps the body-local coordinate system into the world.

```java
@Override public List<AeroSurface> aerodynamicSurfaces() {
    return List.of(
        AeroSurface.wing(new Vec3(-1, 0, 0), new Vec3(0, 0, 1),
            new Vec3(1, 0, 0), 1.0, 5.0, 0),
        AeroSurface.wing(new Vec3(1, 0, 0), new Vec3(0, 0, 1),
            new Vec3(1, 0, 0), 1.0, 5.0, 0));
}
@Override public Vec3 windVelocity() { return Vec3.ZERO; } // blocks/tick
@Override public double airDensity() { return 1.225; }
```

Chord and span are orthonormalized. Choose their signs consistently: chord cross
span defines the positive surface normal. Area is m², aspect ratio is dimensionless,
and the wing factory takes incidence in degrees. The record constructor takes
angles in radians. Control surfaces can return updated local axes/points each tick;
cache unchanged definitions in the adapter rather than rebuilding model geometry.

`externalWrench()` returns world force in N and world torque in N·m.
`Wrench.atPoint(force,point,center)` adds the correct force-arm moment.
`PropellerForce.at(center,axis,bodyCenter,rpm,diameter,Ct,Cq,density,spin)` computes
rotor thrust and reaction torque. Points/diameter use metres; spin is +1 or -1.
A controller supplies RPM; the helper is not an autopilot, motor ramp or fuel model.
The new load path is used when automatic or authored surface aerodynamics is active
and `nativeForcesEnabled()` is true. Disable native forces only if your controller
already supplies the full force integration; do not integrate gravity twice.

Use `applyNativePose(position,quaternion)` to store exact orientation in companion
mods. Derive display Euler angles if needed, but never reconstruct the authoritative
quaternion from those angles every frame. The existing adapter defaults remain for
compatibility. Read [the ABI reference](NATIVE_ABI.md) for low-level force batches.

## Air War presentation

Air War sends position, velocity, quaternion and angular velocity in one synchronized
pose sample. Rendering interpolates whole quaternions instead of separate yaw/pitch/
roll values, including through vertical orientation. Missing packets extrapolate for
at most two ticks. Distant proxies use the same pose representation. Physical Vikhr
rendering no longer adds a decorative axial rotation on top of actual physics.
Passive `/ctaw_physics` bodies use Physicalist forces. Powered guidance remains an
Air War controller; this update does not claim that all flight controllers have been
replaced with MCS behaviour.

## What was learned from the supplied FPV pack

The inspected local `FPV To Minecraft Beta-1.21.1-V2.0-Bugfix9.jar` is a content pack:
its behaviour is configured in JSON and executed by MCS. The manifest version is
2.0.0-beta.10. The following are configuration/static-code findings, not a recorded
in-game flight test of that pack:

- `data/fpvdrones/projectiles/kab20l.json` describes a falling store with drag,
  alignment stiffness/damping and delayed guidance. Decorative axial roll and
  flutter are disabled. Drop stabilization and steering are separate from release.
- `data/fpvdrones/platforms/forpost.json` supplies explicit main wings, tail and fins:
  force application points, areas, aspect ratios, incidence and control axes.
- `data/fpvdrones/platforms/mavic.json` supplies mass/gyration, separate rotors and
  self-level/altitude controller options. This is not the same model as a dropped KAB.
- Static MCS inspection shows native body integration and pose synchronization as
  separate responsibilities from propulsion, aerodynamic loads and guidance.

Physicalist now exposes corresponding building blocks independently: surface forces,
rotor loads, force-arm moments and quaternion poses. It retains server authority,
volume-based compound inertia and its existing terrain/joint integration. MCS client
ownership, authored per-bone mass distributions, full vehicle controllers and combat
systems are not replicated. No code, tuning tables, textures or DLLs from either
supplied mod are redistributed.
