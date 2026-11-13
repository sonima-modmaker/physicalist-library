mod softbody;
use jni::{
    objects::{JClass, JDoubleArray, JLongArray},
    sys::{jdouble, jdoubleArray, jint, jlong},
    JNIEnv,
};
use rapier::math::{Pose, Rotation};
use rapier::prelude::*;
use std::{
    collections::{HashMap, HashSet},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
};

const STRIDE: usize = 15;
/// Full collision-detection steps per tick; the solver substeps inside each.
const OUTER: usize = 4;
const ITERATIONS: usize = 8;
struct Scene {
    world: PhysicsWorld,
    bodies: HashMap<i64, RigidBodyHandle>,
    tiles: HashMap<i64, Vec<ColliderHandle>>,
    next: i64,
    frame: HashMap<i64, Vec<f64>>,
    air: HashMap<i64, Vec<f64>>,
    ignored: HashMap<RigidBodyHandle, [f64; 6]>,
    /// Owner pairs that never touch: a chain and whatever it is attached to.
    apart: HashSet<(u64, u64)>,
    friction: Vec<(RigidBodyHandle, RigidBodyHandle, f64)>,
    water: HashMap<i64, Vec<f64>>,
    /// Bodies the wand froze in place (turned fixed by `sync`).
    frozen: HashSet<i64>,
}
impl Scene {
    fn new(gravity: f64) -> Self {
        let mut world = PhysicsWorld::default();
        world.gravity = Vector::new(0., -gravity, 0.);
        world.integration_parameters.num_solver_iterations = ITERATIONS;
        world.integration_parameters.num_internal_pgs_iterations = 2;
        world.integration_parameters.normalized_allowed_linear_error = 0.0005;
        world
            .integration_parameters
            .contact_softness
            .natural_frequency = 120.;
        world.integration_parameters.contact_softness.damping_ratio = 1.;
        world.integration_parameters.warmstart_joints = true;
        world.integration_parameters.max_ccd_substeps = 4;
        Self {
            world,
            bodies: HashMap::new(),
            tiles: HashMap::new(),
            next: 1,
            frame: HashMap::new(),
            air: HashMap::new(),
            ignored: HashMap::new(),
            apart: HashSet::new(),
            friction: Vec::new(),
            water: HashMap::new(),
            frozen: HashSet::new(),
        }
    }
    /// Archimedes buoyancy and water drag for one substep. `spec` = [water/body density ratio,
    /// linear drag 1/s, angular drag 1/s, surface height per collision part (NaN = dry)].
    /// Forces (not velocity writes) so articulated ragdoll links respond too; returns what was added.
    fn buoyancy(&mut self, id: i64, spec: &[f64]) -> (Vector, Vector) {
        let zero = Vector::new(0., 0., 0.);
        let Some(&h) = self.bodies.get(&id) else {
            return (zero, zero);
        };
        let body = &self.world.bodies[h];
        if !body.is_dynamic() || body.is_sleeping() && spec[0] <= 1. {
            return (zero, zero);
        }
        let com = body.center_of_mass();
        let mut parts = Vec::new();
        let mut total = 0.;
        for &c in body.colliders() {
            let collider = &self.world.colliders[c];
            let Some(cuboid) = collider.shape().as_cuboid() else {
                continue;
            };
            let e = cuboid.half_extents;
            let volume = 8. * e.x * e.y * e.z;
            total += volume;
            let part = (collider.user_data & 0x7fff_ffff) as usize;
            let level = spec.get(3 + part).copied().unwrap_or(f64::NAN);
            if level.is_finite() {
                parts.push((*collider.position(), e, volume, level));
            }
        }
        if parts.is_empty() || total <= 0. {
            return (zero, zero);
        }
        const N: usize = 4;
        let (mut wet, mut moment) = (0., zero);
        for (pose, e, volume, level) in parts {
            let cell = volume / (N * N * N) as f64;
            for i in 0..N * N * N {
                let f = |k: usize| ((k % N) as f64 + 0.5) / N as f64 * 2. - 1.;
                let p = pose.transform_point(Vector::new(
                    f(i) * e.x,
                    f(i / N) * e.y,
                    f(i / (N * N)) * e.z,
                ));
                if p.y < level {
                    wet += cell;
                    moment += p * cell;
                }
            }
        }
        if wet <= 0. {
            return (zero, zero);
        }
        let fraction = (wet / total).min(1.);
        let mass = body.mass();
        let g = -self.world.gravity.y * body.gravity_scale();
        let lift = Vector::new(0., mass * g * spec[0] * fraction, 0.);
        let center = moment / wet;
        let drag = -body.linvel() * (mass * spec[1] * fraction);
        // Box inertia estimate from the body's volume; exact inertia is not needed for damping.
        let inertia = mass * total.powf(2. / 3.) / 6.;
        let force = lift + drag;
        let torque = (center - com).cross(lift) - body.angvel() * (inertia * spec[2] * fraction);
        let wake = spec[0] > 1.;
        let body = &mut self.world.bodies[h];
        body.add_force(force, wake);
        body.add_torque(torque, wake);
        (force, torque)
    }
    /// Rapier leaves coupled angular motors unimplemented, so joint friction is applied here:
    /// relative spin across each joint decays at `rate` per second, conserving angular momentum.
    fn joint_friction(&mut self, dt: f64) {
        let bodies = &mut self.world.bodies;
        self.friction
            .retain(|(a, b, _)| bodies.get(*a).is_some() && bodies.get(*b).is_some());
        for &(a, b, rate) in &self.friction {
            let spin = |h: RigidBodyHandle, rel: Vector| {
                let r = &bodies[h];
                if r.is_dynamic() {
                    r.mass_properties().effective_world_inv_inertia * rel
                } else {
                    Vector::new(0., 0., 0.)
                }
            };
            let (w1, w2) = (bodies[a].angvel(), bodies[b].angvel());
            let rel = w2 - w1;
            let n = rel.length();
            if n < 1e-9 {
                continue;
            }
            let (d1, d2) = (spin(a, rel), spin(b, rel));
            let k = (d1.length() + d2.length()) / n;
            if k < 1e-12 {
                continue;
            }
            let scale = (1. - (-rate * dt).exp()) / k;
            bodies[a].set_angvel(w1 + d1 * scale, false);
            bodies[b].set_angvel(w2 - d2 * scale, false);
        }
    }
    fn create(
        &mut self,
        mass: f64,
        gravity: f64,
        owner: i64,
        role: i32,
        state: &[f64],
        boxes: &[f64],
    ) -> i64 {
        assert!(mass.is_finite() && mass >= 0. && state.len() >= 13 && boxes.len() % 12 == 0);
        let id = self.next;
        self.next += 1;
        let builder = if mass == 0. {
            RigidBodyBuilder::kinematic_velocity_based()
        } else {
            RigidBodyBuilder::dynamic()
        };
        let builder = if (200..10000).contains(&role) {
            // Chain links are thin: without stronger damping they spin about their long axis
            // and keep swinging long after the endpoints stop moving.
            builder.angular_damping(1.5).linear_damping(0.12)
        } else if role >= 0 {
            builder
                .angular_damping(if role < 6 { 0.7 } else { 0.35 })
                .linear_damping(0.05)
        } else {
            builder
        };
        let rb = builder
            .pose(pose(state, 0))
            .linvel(vec(state, 7))
            .angvel(vec(state, 10))
            .gravity_scale(gravity)
            .ccd_enabled(true)
            .user_data(id as u128)
            .build();
        let handle = self.world.insert_body(rb);
        self.bodies.insert(id, handle);
        self.geometry(id, mass, owner, role, boxes);
        id
    }
    fn geometry(&mut self, id: i64, mass: f64, owner: i64, role: i32, boxes: &[f64]) {
        let Some(&handle) = self.bodies.get(&id) else {
            return;
        };
        let previous = self.world.bodies[handle].colliders().to_vec();
        for h in previous {
            self.world.remove_collider(h);
        }
        let volume: f64 = boxes
            .chunks_exact(12)
            .map(|s| 8. * s[7] * s[8] * s[9])
            .sum();
        let density = if mass > 0. {
            mass / volume.max(1e-12)
        } else {
            0.
        };
        for (part, s) in boxes.chunks_exact(12).enumerate() {
            assert!(s.iter().all(|n| n.is_finite()) && s[7] > 0. && s[8] > 0. && s[9] > 0.);
            let mut portions = vec![(0., s[8], false)];
            if (0..6).contains(&role) && role != 1 {
                let low = -s[8];
                let high = s[8];
                let cut = if role == 0 { low + 0.05 } else { high - 0.175 };
                if cut > low && cut < high {
                    portions = if role == 0 {
                        vec![
                            ((cut + high) * 0.5, (high - cut) * 0.5, false),
                            ((low + cut) * 0.5, (cut - low) * 0.5, true),
                        ]
                    } else {
                        vec![
                            ((low + cut) * 0.5, (cut - low) * 0.5, false),
                            ((cut + high) * 0.5, (high - cut) * 0.5, true),
                        ]
                    };
                }
            }
            for (shift, half, cap) in portions {
                let p = pose(s, 0);
                let local = Pose::from_parts(
                    p.translation + p.rotation * Vector::new(0., shift, 0.),
                    p.rotation,
                );
                let data = ((owner as u64 as u128) << 64)
                    | (((role + 1) as u32 as u128) << 32)
                    | ((cap as u128) << 31)
                    | part as u128;
                let collider = ColliderBuilder::cuboid(s[7], half, s[9])
                    .density(density)
                    .position(local)
                    .friction(s[10])
                    .restitution(s[11])
                    .active_hooks(ActiveHooks::FILTER_CONTACT_PAIRS)
                    .user_data(data)
                    .build();
                self.world.insert_collider(collider, Some(handle));
            }
        }
    }
    fn remove(&mut self, id: i64) {
        if let Some(h) = self.bodies.remove(&id) {
            self.ignored.remove(&h);
            self.world.remove_body(h);
        }
    }
    fn tile(&mut self, key: i64, boxes: &[f64]) {
        if let Some(old) = self.tiles.remove(&key) {
            for h in old {
                self.world.remove_collider(h);
            }
        }
        let mut handles = Vec::new();
        for b in boxes.chunks_exact(6) {
            if !b.iter().all(|n| n.is_finite()) || b[3] <= b[0] || b[4] <= b[1] || b[5] <= b[2] {
                continue;
            }
            let c = ColliderBuilder::cuboid(
                (b[3] - b[0]) * 0.5,
                (b[4] - b[1]) * 0.5,
                (b[5] - b[2]) * 0.5,
            )
            .translation(Vector::new(
                (b[0] + b[3]) * 0.5,
                (b[1] + b[4]) * 0.5,
                (b[2] + b[5]) * 0.5,
            ))
            // Ground grips at least as well as rough stone (Max rule): with 0.6 averaged against a body's own
            // 0.35, blocks and ragdolls skated several blocks along the ground as if on ice.
            .friction(0.9)
            .friction_combine_rule(CoefficientCombineRule::Max)
            .restitution(0.)
            .build();
            handles.push(self.world.insert_collider(c, None));
        }
        if !handles.is_empty() {
            self.tiles.insert(key, handles);
        }
    }
    fn step(&mut self, dt: f64) {
        assert!(dt.is_finite() && dt > 0. && dt <= 0.1);
        self.world.integration_parameters.dt = dt / OUTER as f64;
        self.frame.clear();
        // Apply after pose/velocity synchronization. Air cannot create kinetic energy
        // relative to the wind, even when a light plate has a very small inertia.
        for (id, w) in self.air.drain() {
            if let Some(h) = self.bodies.get(&id) {
                let b = &mut self.world.bodies[*h];
                let f = vec(&w, 0);
                let t = vec(&w, 3);
                let v = b.linvel() - vec(&w, 12);
                let omega = b.angvel();
                let props = b.mass_properties();
                let power = f.dot(v) + t.dot(omega);
                let quadratic = f.dot(f * props.effective_inv_mass)
                    + t.dot(props.effective_world_inv_inertia * t);
                let scale = if quadratic > 1e-15 {
                    (-1.8 * power / (dt * quadratic)).clamp(0., 1.)
                } else {
                    1.
                };
                b.reset_forces(false);
                b.reset_torques(false);
                b.add_force(f * scale + vec(&w, 6), false);
                b.add_torque(t * scale + vec(&w, 9), false);
            }
        }
        let bodies: Vec<(i64, RigidBodyHandle)> = self.bodies.iter().map(|(id, h)| (*id, *h)).collect();
        let water = std::mem::take(&mut self.water);
        let mut applied: HashMap<i64, (Vector, Vector)> = HashMap::new();
        for _ in 0..OUTER {
            // Water forces follow the pose each substep: replace last substep's contribution.
            for (id, spec) in &water {
                if let Some((f, t)) = applied.remove(id) {
                    if let Some(h) = self.bodies.get(id) {
                        let b = &mut self.world.bodies[*h];
                        b.add_force(-f, false);
                        b.add_torque(-t, false);
                    }
                }
                let added = self.buoyancy(*id, spec);
                applied.insert(*id, added);
            }
            self.world.step_with_events(
                &Contacts {
                    ignored: &self.ignored,
                    apart: &self.apart,
                },
                &(),
            );
            self.joint_friction(dt / OUTER as f64);
            // Merged straight into the frame record, in the same order as reading a snapshot first:
            // no per-body, per-substep allocation or id lookup (a quarter of the step time).
            for &(id, handle) in &bodies {
                merge_contacts(&self.world, handle, self.frame.entry(id).or_default());
            }
        }
        for handle in self.bodies.values() {
            let b = &mut self.world.bodies[*handle];
            b.reset_forces(false);
            b.reset_torques(false);
        }
    }
    fn forces(&mut self, id: i64, wrench: &[f64]) {
        assert!((wrench.len() == 6 || wrench.len() == 15) && wrench.iter().all(|v| v.is_finite()));
        if wrench.len() == 15 {
            self.air.insert(id, wrench.to_vec());
            return;
        }
        if let Some(h) = self.bodies.get(&id) {
            let b = &mut self.world.bodies[*h];
            b.reset_forces(false);
            b.reset_torques(false);
            b.add_force(vec(wrench, 0), false);
            b.add_torque(vec(wrench, 3), false);
        }
    }
    fn ignore(&mut self, id: i64, region: &[f64]) {
        assert!((region.is_empty() || region.len() == 6) && region.iter().all(|n| n.is_finite()));
        if let Some(handle) = self.bodies.get(&id) {
            if region.is_empty() {
                self.ignored.remove(handle);
            } else {
                self.ignored.insert(*handle, region.try_into().unwrap());
            }
        }
    }
    fn read(&self, ids: &[i64]) -> Vec<f64> {
        let mut out = vec![0.; ids.len() * STRIDE];
        for (i, id) in ids.iter().enumerate() {
            if let Some(h) = self.bodies.get(id) {
                let b = &self.world.bodies[*h];
                let p = b.translation();
                let q = b.rotation();
                let v = b.linvel();
                let w = b.angvel();
                out[i * STRIDE..(i + 1) * STRIDE].copy_from_slice(&[
                    p.x,
                    p.y,
                    p.z,
                    q.x,
                    q.y,
                    q.z,
                    q.w,
                    v.x,
                    v.y,
                    v.z,
                    w.x,
                    w.y,
                    w.z,
                    b.gravity_scale(),
                    if b.is_sleeping() { 1. } else { 0. },
                ]);
            }
        }
        out
    }
    fn sync(&mut self, ids: &[i64], states: &[f64]) {
        assert_eq!(states.len(), ids.len() * STRIDE);
        for (i, id) in ids.iter().enumerate() {
            let Some(h) = self.bodies.get(id) else {
                continue;
            };
            let s = &states[i * STRIDE..(i + 1) * STRIDE];
            assert!(s.iter().all(|x| x.is_finite()));
            let flags = s[14] as i32;
            let b = &mut self.world.bodies[*h];
            if flags & 8 != 0 {
                b.set_enabled(false);
                continue;
            }
            if flags & 4 != 0 {
                b.set_enabled(true);
            }
            // Frozen (wand): a fixed body others still collide with; it turns dynamic again
            // once the flag is gone. Only bodies frozen this way are turned back.
            if flags & 16 != 0 {
                if !b.is_fixed() {
                    b.set_body_type(RigidBodyType::Fixed, true);
                    self.frozen.insert(*id);
                }
            } else if self.frozen.remove(id) {
                b.set_body_type(RigidBodyType::Dynamic, true);
            }
            if flags & 1 != 0 {
                b.set_position(pose(s, 0), true);
            }
            if flags & 2 != 0 {
                b.set_linvel(vec(s, 7), true);
                b.set_angvel(vec(s, 10), true);
            }
            b.set_gravity_scale(s[13], false);
            // Articulated bodies own a reduced velocity vector; writing only the link RB is ignored.
            if flags & 2 != 0 {
                if let Some(link) = self.world.multibody_joints.rigid_body_link(*h).copied() {
                    if link.id == 0 {
                        if let Some(mb) = self
                            .world
                            .multibody_joints
                            .get_multibody_mut(link.multibody)
                        {
                            let mut velocity = mb.generalized_velocity_mut();
                            if velocity.len() >= 6 {
                                for axis in 0..6 {
                                    velocity[axis] = s[7 + axis];
                                }
                            }
                        }
                    } else if let Some(mb) = self
                        .world
                        .multibody_joints
                        .get_multibody_mut(link.multibody)
                    {
                        let limb = *mb.link(link.id).unwrap();
                        if limb.joint.ndofs() == 3
                            && limb.joint.data.locked_axes == JointAxesMask::LOCKED_SPHERICAL_AXES
                        {
                            let parent = mb
                                .link(limb.parent_id().unwrap())
                                .unwrap()
                                .rigid_body_handle();
                            let frame = *self.world.bodies[parent].rotation()
                                * limb.joint.data.local_frame1.rotation;
                            let relative =
                                frame.inverse() * (vec(s, 10) - self.world.bodies[parent].angvel());
                            let offset = limb.assembly_id();
                            let mut velocities = mb.generalized_velocity_mut();
                            for axis in 0..3 {
                                velocities[offset + axis] = relative[axis];
                            }
                        }
                    }
                }
            }
        }
    }
    fn joint(&mut self, a: i64, b: i64, s: &[f64]) -> i64 {
        assert!(s.len() >= 12);
        let (Some(&a), Some(&b)) = (self.bodies.get(&a), self.bodies.get(&b)) else {
            return 0;
        };
        if s[0] == 4. {
            let mut rope = RopeJoint::new(s[7]);
            rope.set_local_anchor1(vec(s, 1));
            rope.set_local_anchor2(vec(s, 4));
            rope.set_contacts_enabled(true);
            let h = self.world.insert_impulse_joint(a, b, rope.data);
            let (i, g) = h.into_raw_parts();
            return (((g as u64) << 32) | i as u64) as i64;
        }
        let mask = match s[0] as i32 {
            1 => JointAxesMask::LOCKED_REVOLUTE_AXES,
            2 => JointAxesMask::LOCKED_PRISMATIC_AXES,
            3 => JointAxesMask::LOCKED_FIXED_AXES,
            _ => JointAxesMask::LOCKED_SPHERICAL_AXES,
        };
        let mut j = GenericJointBuilder::new(mask)
            .local_anchor1(vec(s, 1))
            .local_anchor2(vec(s, 4))
            .contacts_enabled(s.get(16).copied().unwrap_or(0.) == 0.);
        if s.len() >= 15 {
            let axis = vec(s, 12).normalize();
            j = j.local_axis1(axis).local_axis2(axis);
        }
        // Anatomical socket (s[18] != 0): separate ranges for twist about the limb's own
        // (local y) axis and for swinging it forward/back (about local x) and sideways (about
        // local z), as shoulders, hips and the neck move. The joint frame maps joint X to the
        // body's y, joint Y to its x and joint Z to its -z (a half turn about (1,1,0)).
        if s[0] == 0. && s.len() >= 25 && s[18] != 0. {
            let h = std::f64::consts::FRAC_1_SQRT_2;
            let r = Rotation::from_xyzw(h, h, 0., 0.);
            j = j
                .local_frame1(Pose::from_parts(vec(s, 1), r))
                .local_frame2(Pose::from_parts(vec(s, 4), r))
                .softness(SpringCoefficients::new(40., 1.))
                .limits(JointAxis::AngX, [s[19], s[20]])
                .limits(JointAxis::AngY, [s[21], s[22]])
                .limits(JointAxis::AngZ, [-s[24], -s[23]]);
            // Joint friction (s[11], 1/s): limbs swing loose but do not rattle against their stops.
            // Only the momentum-conserving decay of `joint_friction`: a velocity motor on these
            // uncoupled axes is far stiffer and held an arm from ever rising over the head.
            if s[10] == 0. && s[11] > 0. {
                self.friction.push((a, b, s[11]));
            }
        } else if s[0] == 0. {
            j = j
                .coupled_axes(JointAxesMask::ANG_Y | JointAxesMask::ANG_Z)
                .softness(if s.get(17).copied().unwrap_or(0.) != 0. {
                    SpringCoefficients::joint_defaults()
                } else {
                    SpringCoefficients::new(40., 1.)
                });
            for (axis, limit) in [(JointAxis::AngX, s[7]), (JointAxis::AngY, s[9])] {
                if limit >= 0. {
                    j = j.limits(axis, [-limit, limit]);
                }
            }
            if s[10] == 0. && s[11] > 0. {
                for axis in [JointAxis::AngX, JointAxis::AngY, JointAxis::AngZ] {
                    j = j.motor_velocity(axis, 0., s[11]);
                }
                self.friction.push((a, b, s[11]));
            }
        } else if s[0] == 1. {
            j = j.limits(JointAxis::AngX, [s[7], s[8]]);
        } else if s[0] == 2. {
            j = j.limits(JointAxis::LinX, [s[7], s[8]]);
        }
        if s[10] > 0. {
            let axis = if s[0] == 2. {
                JointAxis::LinX
            } else {
                JointAxis::AngX
            };
            j = j.motor_position(axis, 0., s[10], s[11]);
        }
        if s.len() > 15 && s[15] != 0. {
            let h = self
                .world
                .insert_multibody_joint(a, b, j)
                .expect("Articulated joint requires an acyclic body graph");
            // New articulations have their own velocity coordinates, initially zero.
            // Preserve the root/carrier momentum when attaching the first links.
            if let Some((mb, child_id)) = self.world.multibody_joints.get_mut(h) {
                let root = &self.world.bodies[mb.root().rigid_body_handle()];
                let linear = root.linvel();
                let angular = root.angvel();
                let child = *mb.link(child_id).unwrap();
                let initial = if child.joint.ndofs() == 3
                    && child.joint.data.locked_axes == JointAxesMask::LOCKED_SPHERICAL_AXES
                {
                    let parent = mb
                        .link(child.parent_id().unwrap())
                        .unwrap()
                        .rigid_body_handle();
                    let frame = *self.world.bodies[parent].rotation()
                        * child.joint.data.local_frame1.rotation;
                    Some((
                        child.assembly_id(),
                        frame.inverse()
                            * (self.world.bodies[b].angvel() - self.world.bodies[parent].angvel()),
                    ))
                } else {
                    None
                };
                let mut velocity = mb.generalized_velocity_mut();
                if let Some((offset, relative)) = initial {
                    for axis in 0..3 {
                        velocity[offset + axis] = relative[axis];
                    }
                }
                if velocity.len() >= 6 {
                    for axis in 0..3 {
                        velocity[axis] = linear[axis];
                        velocity[axis + 3] = angular[axis];
                    }
                }
            }
            let (i, g) = h.into_raw_parts();
            return (((g as u64) << 32) | i as u64) as i64;
        }
        let h = self.world.insert_impulse_joint(a, b, j);
        let (i, g) = h.into_raw_parts();
        (((g as u64) << 32) | i as u64) as i64
    }
    fn joint_force(&self, id: i64) -> Vec<f64> {
        let h = ImpulseJointHandle::from_raw_parts(id as u32, ((id as u64) >> 32) as u32);
        if let Some(joint) = self.world.impulse_joints.get(h) {
            if joint.data.coupled_axes.contains(JointAxesMask::LIN_AXES) {
                let a = self.world.bodies[joint.body1()].position() * joint.data.local_anchor1();
                let b = self.world.bodies[joint.body2()].position() * joint.data.local_anchor2();
                let delta = b - a;
                if delta.length_squared() > 1e-12 {
                    let force = delta.normalize()
                        * (-joint.data.limits[0].impulse.abs()
                            * self.world.integration_parameters.num_solver_iterations as f64
                            / self.world.integration_parameters.dt);
                    return vec![force.x, force.y, force.z];
                }
                return vec![0., 0., 0.];
            }
            let impulse = Vector::new(joint.impulses[0], joint.impulses[1], joint.impulses[2]);
            let force = (*self.world.bodies[joint.body1()].rotation()
                * joint.data.local_frame1.rotation)
                * impulse
                * -(self.world.integration_parameters.num_solver_iterations as f64)
                / self.world.integration_parameters.dt;
            vec![force.x, force.y, force.z]
        } else {
            vec![0., 0., 0.]
        }
    }
    fn contacts(&self, id: i64) -> Vec<f64> {
        self.frame.get(&id).cloned().unwrap_or_default()
    }
    fn contacts_snapshot(&self, id: i64) -> Vec<f64> {
        let Some(&handle) = self.bodies.get(&id) else {
            return vec![];
        };
        let mut out = Vec::new();
        let body = &self.world.bodies[handle];
        for &collider in body.colliders() {
            for pair in self.world.contact_pairs_with(collider) {
                if !pair.has_any_active_contact() {
                    continue;
                }
                let first = pair.collider1 == collider;
                let own = &self.world.colliders[collider];
                let other = &self.world.colliders[if first {
                    pair.collider2
                } else {
                    pair.collider1
                }];
                let other_id = other
                    .parent()
                    .map(|h| self.world.bodies[h].user_data as i64)
                    .unwrap_or(0);
                for m in pair.manifolds() {
                    let normal = if first { -m.data.normal } else { m.data.normal };
                    for p in &m.points {
                        if p.dist > 0.01 {
                            continue;
                        }
                        let local = if first { p.local_p1 } else { p.local_p2 };
                        let point = own.position().transform_point(local);
                        out.extend_from_slice(&[
                            (own.user_data & 65535) as f64,
                            other_id as f64,
                            normal.x,
                            normal.y,
                            normal.z,
                            point.x,
                            point.y,
                            point.z,
                            (-p.dist).max(0.),
                            p.data.impulse,
                        ]);
                        if out.len() >= 640 {
                            return out;
                        }
                    }
                }
            }
        }
        out
    }
}
/// Folds one substep's touching contacts of a body into its frame record: a contact on the same
/// part, against the same body, with a similar normal adds its impulse to the existing entry.
/// Reads at most 64 contacts per substep and keeps at most 64 per frame, like `contacts_snapshot`.
fn merge_contacts(world: &PhysicsWorld, handle: RigidBodyHandle, saved: &mut Vec<f64>) {
    let mut read = 0;
    let body = &world.bodies[handle];
    for &collider in body.colliders() {
        for pair in world.contact_pairs_with(collider) {
            if !pair.has_any_active_contact() {
                continue;
            }
            let first = pair.collider1 == collider;
            let own = &world.colliders[collider];
            let other = &world.colliders[if first { pair.collider2 } else { pair.collider1 }];
            let other_id = other.parent().map(|h| world.bodies[h].user_data as i64).unwrap_or(0);
            for m in pair.manifolds() {
                let normal = if first { -m.data.normal } else { m.data.normal };
                for p in &m.points {
                    if p.dist > 0.01 {
                        continue;
                    }
                    let local = if first { p.local_p1 } else { p.local_p2 };
                    let point = own.position().transform_point(local);
                    let contact = [
                        (own.user_data & 65535) as f64,
                        other_id as f64,
                        normal.x,
                        normal.y,
                        normal.z,
                        point.x,
                        point.y,
                        point.z,
                        (-p.dist).max(0.),
                        p.data.impulse,
                    ];
                    let existing = saved.chunks_exact_mut(10).find(|old| {
                        old[0] == contact[0]
                            && old[1] == contact[1]
                            && old[2] * contact[2] + old[3] * contact[3] + old[4] * contact[4] > 0.9
                    });
                    if let Some(old) = existing {
                        old[9] += contact[9];
                        old[8] = old[8].max(contact[8]);
                    } else if saved.len() < 640 {
                        saved.extend_from_slice(&contact);
                    }
                    read += 1;
                    if read >= 64 {
                        return;
                    }
                }
            }
        }
    }
}
struct Contacts<'a> {
    ignored: &'a HashMap<RigidBodyHandle, [f64; 6]>,
    apart: &'a HashSet<(u64, u64)>,
}
fn owner_pair(a: u64, b: u64) -> (u64, u64) {
    (a.min(b), a.max(b))
}
impl PhysicsHooks for Contacts<'_> {
    fn filter_contact_pair(&self, c: &PairFilterContext) -> Option<SolverFlags> {
        for (own, other) in [(c.collider1, c.collider2), (c.collider2, c.collider1)] {
            if let Some(parent) = c.colliders[own].parent() {
                if let Some(region) = self.ignored.get(&parent) {
                    let bounds = c.colliders[other].compute_aabb();
                    if (0..3).all(|axis| {
                        bounds.mins[axis] >= region[axis] - 0.001
                            && bounds.maxs[axis] <= region[axis + 3] + 0.001
                    }) {
                        return None;
                    }
                }
            }
        }
        let a = c.colliders[c.collider1].user_data;
        let b = c.colliders[c.collider2].user_data;
        let ra = ((a >> 32) & 0xffff) as u32;
        let rb = ((b >> 32) & 0xffff) as u32;
        if (ra == 10001 && (!(201..=265).contains(&rb) || a >> 64 != b >> 64))
            || (rb == 10001 && (!(201..=265).contains(&ra) || a >> 64 != b >> 64))
        {
            return None;
        }
        let (oa, ob) = ((a >> 64) as u64, (b >> 64) as u64);
        // A chain end sits inside its holder; contacts there pushed the holder away and the chain
        // followed, so both flew off together.
        if oa != 0 && ob != 0 && oa != ob && self.apart.contains(&owner_pair(oa, ob)) {
            return None;
        }
        let owner = a >> 64;
        if owner != 0 && owner == b >> 64 {
            let ar = ((a >> 32) & 0xffff) as u32;
            let br = ((b >> 32) & 0xffff) as u32;
            if ((a >> 31) & 1 != 0 && br == 2) || ((b >> 31) & 1 != 0 && ar == 2) {
                return None;
            }
            // Nearby links of one chain overlap whenever it bends; their contacts fight the joints.
            if (201..=265).contains(&ar) && (201..=265).contains(&br) && ar.abs_diff(br) <= 3 {
                return None;
            }
        }
        Some(SolverFlags::COMPUTE_RIGID_IMPULSES)
    }
}
fn vec(s: &[f64], i: usize) -> Vector {
    Vector::new(s[i], s[i + 1], s[i + 2])
}
fn pose(s: &[f64], i: usize) -> Pose {
    Pose::from_parts(
        vec(s, i),
        Rotation::from_xyzw(s[i + 3], s[i + 4], s[i + 5], s[i + 6]).normalize(),
    )
}
static SCENES: OnceLock<Mutex<HashMap<i64, Scene>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn scenes() -> &'static Mutex<HashMap<i64, Scene>> {
    SCENES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn doubles(e: &JNIEnv, a: &JDoubleArray) -> Vec<f64> {
    let n = e.get_array_length(a).unwrap_or(0) as usize;
    if n >= 2000000 {
        return vec![];
    }
    let mut v = vec![0.; n];
    if e.get_double_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v
}
fn longs(e: &JNIEnv, a: &JLongArray) -> Vec<i64> {
    let n = e.get_array_length(a).unwrap_or(0) as usize;
    if n >= 100000 {
        return vec![];
    }
    let mut v = vec![0; n];
    if e.get_long_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v
}
fn output(e: &JNIEnv, v: &[f64]) -> jdoubleArray {
    let a = e.new_double_array(v.len() as i32).unwrap();
    e.set_double_array_region(&a, 0, v).unwrap();
    a.into_raw()
}
fn guard<T: Default>(e: &mut JNIEnv, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            let _ = e.throw_new(
                "java/lang/IllegalStateException",
                "Physicalist native scene operation failed",
            );
            T::default()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_abi0(
    _: JNIEnv,
    _: JClass,
) -> jint {
    6
}
/// Removes an impulse joint (a broken bone's socket, before it is rejoined loosely).
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_removeJoint0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    joint: jlong,
) {
    guard(&mut e, || {
        let mut scenes = scenes().lock().unwrap_or_else(|p| p.into_inner());
        let s = scenes.get_mut(&scene).unwrap();
        let h = ImpulseJointHandle::from_raw_parts(joint as u32, ((joint as u64) >> 32) as u32);
        if let Some(j) = s.world.impulse_joints.remove(h, true) {
            let (a, b) = (j.body1(), j.body2());
            s.friction.retain(|(x, y, _)| !(*x == a && *y == b));
        }
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_createScene0(
    mut e: JNIEnv,
    _: JClass,
    g: jdouble,
) -> jlong {
    guard(&mut e, || {
        assert!(g.is_finite() && g >= 0.);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(id, Scene::new(g));
        id
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_destroyScene0(
    mut e: JNIEnv,
    _: JClass,
    id: jlong,
) {
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&id);
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_createBody0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    mass: jdouble,
    g: jdouble,
    owner: jlong,
    role: jint,
    state: JDoubleArray,
    boxes: JDoubleArray,
) -> jlong {
    let state = doubles(&e, &state);
    let boxes = doubles(&e, &boxes);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .create(mass, g, owner, role, &state, &boxes)
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_geometry0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
    mass: jdouble,
    owner: jlong,
    role: jint,
    boxes: JDoubleArray,
) {
    let boxes = doubles(&e, &boxes);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .geometry(id, mass, owner, role, &boxes)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_removeBody0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
) {
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .remove(id)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_terrain0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    key: jlong,
    boxes: JDoubleArray,
) {
    let boxes = doubles(&e, &boxes);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .tile(key, &boxes)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_sync0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    ids: JLongArray,
    state: JDoubleArray,
) {
    let ids = longs(&e, &ids);
    let state = doubles(&e, &state);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .sync(&ids, &state)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_step0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    dt: jdouble,
) {
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .step(dt)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_read0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    ids: JLongArray,
) -> jdoubleArray {
    let ids = longs(&e, &ids);
    let v = guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&scene)
            .unwrap()
            .read(&ids)
    });
    output(&e, &v)
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_joint0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    a: jlong,
    b: jlong,
    p: JDoubleArray,
) -> jlong {
    let p = doubles(&e, &p);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .joint(a, b, &p)
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_contacts0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
) -> jdoubleArray {
    let v = guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&scene)
            .unwrap()
            .contacts(id)
    });
    output(&e, &v)
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_forces0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
    wrench: JDoubleArray,
) {
    let w = doubles(&e, &wrench);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .forces(id, &w)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_water0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
    spec: JDoubleArray,
) {
    let values = doubles(&e, &spec);
    guard(&mut e, || {
        assert!(values.len() >= 3 && values[..3].iter().all(|v| v.is_finite() && *v >= 0.));
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .water
            .insert(id, values);
    });
}
/// Replaces the set of owner pairs that never collide, as flat [ownerA, ownerB, ...].
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_apart0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    pairs: JLongArray,
) {
    let n = e.get_array_length(&pairs).unwrap_or(0) as usize;
    let mut values = vec![0i64; n];
    let _ = e.get_long_array_region(&pairs, 0, &mut values);
    guard(&mut e, || {
        let mut scenes = scenes().lock().unwrap_or_else(|p| p.into_inner());
        let s = scenes.get_mut(&scene).unwrap();
        s.apart = values
            .chunks_exact(2)
            .map(|p| owner_pair(p[0] as u64, p[1] as u64))
            .collect();
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_ignore0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    id: jlong,
    region: JDoubleArray,
) {
    let values = doubles(&e, &region);
    guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get_mut(&scene)
            .unwrap()
            .ignore(id, &values)
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeBridge_jointForce0(
    mut e: JNIEnv,
    _: JClass,
    scene: jlong,
    joint: jlong,
) -> jdoubleArray {
    let values = guard(&mut e, || {
        scenes()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&scene)
            .unwrap()
            .joint_force(joint)
    });
    output(&e, &values)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn state(x: f64, y: f64) -> [f64; 15] {
        [x, y, 0., 0., 0., 0., 1., 0., 0., 0., 0., 0., 0., 1., 0.]
    }
    fn cube() -> [f64; 12] {
        [0., 0., 0., 0., 0., 0., 1., 0.5, 0.5, 0.5, 0.6, 0.]
    }
    /// Pile of 125 cubes on terrain tiles, the shape of the Java BenchmarkGameTests scene.
    fn bench_pile() -> (Scene, Vec<i64>) {
        let mut s = Scene::new(9.81);
        for x in -1..2 {
            for z in -1..2 {
                let (x, z) = (x as f64 * 6., z as f64 * 6.);
                s.tile(((x as i64) << 8) ^ z as i64, &[x - 3., -1., z - 3., x + 3., 0., z + 3.]);
            }
        }
        let mut ids = Vec::new();
        for layer in 0..5 {
            for x in 0..5 {
                for z in 0..5 {
                    let mut st = state(x as f64 - 2., 2.5 + layer as f64 * 2.);
                    st[2] = z as f64 - 2.;
                    ids.push(s.create(1., 1., 1000 + ids.len() as i64, -1, &st, &cube()));
                }
            }
        }
        (s, ids)
    }
    fn checksum(s: &Scene, ids: &[i64]) -> u64 {
        let mut hash = 1469598103934665603u64;
        for v in s.read(ids) {
            hash ^= v.to_bits();
            hash = hash.wrapping_mul(1099511628211);
        }
        hash
    }
    /// cargo test --release bench_step -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_step() {
        let (mut full, ids) = bench_pile();
        let started = std::time::Instant::now();
        for _ in 0..300 {
            full.step(0.05);
        }
        let total = started.elapsed();
        let (mut bare, _) = bench_pile();
        let started = std::time::Instant::now();
        for _ in 0..300 {
            bare.world.integration_parameters.dt = 0.05 / OUTER as f64;
            for _ in 0..OUTER {
                bare.world.step_with_events(&Contacts { ignored: &bare.ignored, apart: &bare.apart }, &());
                bare.joint_friction(0.05 / OUTER as f64);
            }
        }
        let rapier = started.elapsed();
        println!(
            "step(): {:.3} ms/tick, Rapier alone: {:.3} ms/tick, checksum {:016x}",
            total.as_secs_f64() * 1000. / 300.,
            rapier.as_secs_f64() * 1000. / 300.,
            checksum(&full, &ids)
        );
    }
    /// Four swinging 64-link chains over terrain plus two ragdoll-sized bodies, all awake:
    /// cargo test --release bench_chains -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_chains() {
        let mut s = Scene::new(9.81);
        s.tile(1, &[-20., -1., -20., 20., 0., 20.]);
        let link = [0., 0., 0., 0., 0., 0., 1., 0.05, 0.27, 0.05, 0.5, 0.];
        let mut anchors = Vec::new();
        for c in 0..4 {
            let x = c as f64 * 3.;
            let anchor = s.create(0., 0., 10 + c, 264, &state(x, 30.), &[]);
            anchors.push(anchor);
            let mut parent = anchor;
            for i in 0..64 {
                let id = s.create(0.4, 1., 10 + c, 200 + i, &state(x + 0.5 * i as f64, 30.), &link);
                let top = if i == 0 { 0. } else { 0.27 };
                s.joint(parent, id, &[0., 0., top, 0., 0., -0.27, 0., -1., -1., -1., 0., 10., 0., 1., 0., 0., 1., 1.]);
                parent = id;
            }
        }
        let started = std::time::Instant::now();
        for frame in 0..200 {
            for &a in &anchors {
                let mut st = state(0., 30.);
                st[7] = if frame % 40 < 20 { 3. } else { -3. };
                st[13] = 0.;
                st[14] = 6.;
                s.sync(&[a], &st);
            }
            s.step(0.05);
        }
        println!("chains step(): {:.3} ms/tick", started.elapsed().as_secs_f64() * 1000. / 200.);
    }
    #[test]
    fn falls_and_sleeps_on_terrain() {
        let mut s = Scene::new(9.81);
        s.tile(1, &[-5., -1., -5., 5., 0., 5.]);
        let id = s.create(1., 1., 1, -1, &state(0., 5.), &cube());
        for _ in 0..240 {
            s.step(0.05);
        }
        let r = s.read(&[id]);
        assert!((r[1] - 0.5).abs() < 0.02, "y={}", r[1]);
        assert_eq!(r[14], 1.);
    }
    #[test]
    fn stack_does_not_explode() {
        let mut s = Scene::new(9.81);
        s.tile(1, &[-5., -1., -5., 5., 0., 5.]);
        let a = s.create(1., 1., 1, -1, &state(0., 0.6), &cube());
        let b = s.create(1., 1., 2, -1, &state(0., 1.7), &cube());
        for _ in 0..200 {
            s.step(0.05);
        }
        let r = s.read(&[a, b]);
        assert!(r[1] > 0.45 && r[16] > 1.4);
        assert!(r.iter().all(|v| v.is_finite()));
    }
    #[test]
    fn off_center_compound_topples() {
        let mut s = Scene::new(9.81);
        s.tile(1, &[12000004., 0., 0., 12000005., 1., 1.]);
        let mut boxes = Vec::new();
        for x in 0..5 {
            let mut b = cube();
            b[0] = x as f64 + 0.5;
            b[1] = 1.5;
            b[2] = 0.5;
            boxes.extend(b);
        }
        for y in 2..5 {
            let mut b = cube();
            b[0] = 4.5;
            b[1] = y as f64 + 0.5;
            b[2] = 0.5;
            boxes.extend(b);
        }
        let id = s.create(8., 1., 1, -1, &state(12000000., 0.), &boxes);
        for _ in 0..110 {
            s.step(0.05);
        }
        let r = s.read(&[id]);
        assert!(
            r[3].abs() + r[5].abs() > 0.1,
            "native compound did not topple: {:?}",
            r
        );
    }
    #[test]
    fn articulated_root_inherits_and_accepts_velocity() {
        let mut scene = Scene::new(0.);
        let mut a = state(0., 0.);
        a[7] = 4.;
        let mut b = state(0., 2.);
        b[7] = 4.;
        let root = scene.create(1., 1., 1, -1, &a, &cube());
        let child = scene.create(1., 1., 1, -1, &b, &cube());
        scene.joint(
            root,
            child,
            &[
                0., 0., 1., 0., 0., -1., 0., -1., -1., -1., 0., 0., 0., 1., 0., 1.,
            ],
        );
        scene.step(0.05);
        let mut first = scene.read(&[root]);
        assert!(first[0] > 0.1, "articulation discarded inherited velocity");
        let before = first[0];
        first[7] = 8.;
        first[14] = 6.;
        scene.sync(&[root], &first);
        scene.step(0.05);
        assert!(
            scene.read(&[root])[0] > before + 0.3,
            "articulation ignored tool velocity"
        );
    }
    #[test]
    fn large_coordinates_and_ccd() {
        let mut s = Scene::new(0.);
        let mut p = state(12000000., 4.);
        p[7] = 300.;
        s.tile(1, &[12000001., 0., -1., 12000001.1, 8., 1.]);
        let id = s.create(1., 1., 1, -1, &p, &cube());
        s.step(0.05);
        assert!(s.read(&[id])[0] < 12000001.1);
    }
    #[test]
    fn force_and_moment_use_inertia_and_clear_after_step() {
        let mut scene = Scene::new(0.);
        let id = scene.create(1., 0., 1, -1, &state(0., 0.), &cube());
        scene.step(0.05);
        scene.forces(id, &[1., 0., 0., 0., 0., 0.5]);
        scene.step(0.05);
        let first = scene.read(&[id]);
        assert!((first[7] - 0.05).abs() < 1e-6);
        assert!((first[12] - 0.15).abs() < 1e-6);
        scene.step(0.05);
        let next = scene.read(&[id]);
        assert!((next[7] - first[7]).abs() < 1e-6);
        assert!((next[12] - first[12]).abs() < 1e-6);
    }
    #[test]
    fn upright_body_settles_without_a_spin_generator() {
        let mut scene = Scene::new(9.81);
        scene.tile(1, &[-4., -1., -4., 4., 0., 4.]);
        let mut shape = cube();
        shape[7] = 0.2;
        shape[8] = 2.;
        shape[9] = 0.2;
        let id = scene.create(5., 1., 1, -1, &state(0., 2.01), &shape);
        for _ in 0..300 {
            scene.step(0.05);
        }
        let r = scene.read(&[id]);
        assert!(
            r[10].abs() + r[11].abs() + r[12].abs() < 1e-5,
            "upright body still spins"
        );
        assert!(
            r[6].abs() > 0.9999,
            "upright supported body tipped without an impulse"
        );
        assert_eq!(r[14], 1.);
    }
    #[test]
    fn aerodynamic_moment_cannot_accelerate_a_light_plate() {
        for speed in [0.1, 1., 50., 500.] {
            let mut scene = Scene::new(0.);
            let mut shape = cube();
            shape[7] = 0.5;
            shape[8] = 0.01;
            shape[9] = 0.1;
            let mut start = state(0., 0.);
            start[10] = speed;
            let id = scene.create(0.1, 0., 1, -1, &start, &shape);
            scene.step(0.05);
            for _ in 0..50 {
                let before = scene.read(&[id]);
                let mut wrench = [0.; 15];
                wrench[3] = -before[10] * before[10].abs() * 100.;
                scene.forces(id, &wrench);
                scene.step(0.05);
                let after = scene.read(&[id]);
                assert!(after[10].abs() <= before[10].abs() + 1e-7);
            }
        }
    }
    #[test]
    fn launch_exclusion_does_not_remove_other_walls() {
        let mut scene = Scene::new(0.);
        scene.tile(1, &[0.95, 0., -1., 1.05, 4., 1., 3., 0., -1., 3.1, 4., 1.]);
        let mut p = state(0., 2.);
        p[7] = 100.;
        let id = scene.create(1., 0., 1, -1, &p, &cube());
        scene.ignore(id, &[0.9, -0.1, -1.1, 1.1, 4.1, 1.1]);
        scene.step(0.05);
        let x = scene.read(&[id])[0];
        assert!(x > 1.5 && x < 3., "Exclusion skipped wrong geometry: {x}");
    }
    #[test]
    fn articulated_child_keeps_and_accepts_angular_motion() {
        let mut scene = Scene::new(0.);
        let mut shape = cube();
        shape[7] = 0.1;
        shape[8] = 0.375;
        shape[9] = 0.1;
        let root = scene.create(4., 0., 1, -1, &state(0., 2.), &shape);
        let mut child_state = state(-0.375, 2.);
        child_state[12] = -2.4;
        let child = scene.create(1., 0., 1, -1, &child_state, &shape);
        scene.joint(
            root,
            child,
            &[
                0., -0.25, 0.3, 0., 0.125, 0.3, 0., -1., -1., -1., 0., 0., 0., 1., 0., 1., 1.,
            ],
        );
        for _ in 0..5 {
            scene.step(0.05);
        }
        let r = scene.read(&[root, child]);
        assert!(
            (r[5] - r[20]).abs() > 0.03,
            "Child angular momentum was discarded"
        );
        let mut target = scene.read(&[child]);
        target[12] = -4.;
        target[14] = 6.;
        scene.sync(&[child], &target);
        scene.step(0.05);
        assert!(
            scene.read(&[child])[12] < -0.5,
            "Child angular change ignored"
        );
    }
    #[test]
    fn full_ragdoll_allows_independent_arm_swing() {
        let mut scene = Scene::new(9.81);
        let offsets = [
            (0., 10.625),
            (0., 10.),
            (-0.375, 10.),
            (0.375, 10.),
            (-0.125, 9.25),
            (0.125, 9.25),
        ];
        let mut ids = Vec::new();
        let mut states = Vec::new();
        for i in 0..6 {
            let mut p = state(offsets[i].0, offsets[i].1);
            let mut shape = cube();
            shape[7] = if i < 2 { 0.25 } else { 0.125 };
            shape[8] = if i == 0 { 0.25 } else { 0.375 };
            shape[9] = if i == 0 { 0.25 } else { 0.125 };
            if i == 2 {
                p[7] = -3.6;
                p[8] = 1.5;
                p[12] = -12.;
            }
            ids.push(scene.create(
                if i == 1 {
                    4.
                } else if i == 0 {
                    1.5
                } else {
                    1.
                },
                1.,
                1,
                i as i32,
                &p,
                &shape,
            ));
            states.push(p);
        }
        scene.joint(
            ids[1],
            ids[0],
            &[
                0., 0., 0.375, 0., 0., -0.25, 0., 1.13, 1., 1.13, 0., 0., 0., 1., 0., 1.,
            ],
        );
        for i in 2..4 {
            let side = if i == 2 { -1. } else { 1. };
            scene.joint(
                ids[1],
                ids[i],
                &[
                    0.,
                    side * 0.25,
                    0.3,
                    0.,
                    -side * 0.125,
                    0.3,
                    0.,
                    2.53,
                    2.,
                    2.53,
                    0.,
                    0.,
                    0.,
                    1.,
                    0.,
                    1.,
                ],
            );
        }
        for i in 4..6 {
            let side = if i == 4 { -1. } else { 1. };
            scene.joint(
                ids[1],
                ids[i],
                &[
                    0.,
                    side * 0.125,
                    -0.375,
                    0.,
                    0.,
                    0.375,
                    0.,
                    1.65,
                    1.25,
                    1.65,
                    0.,
                    0.,
                    0.,
                    1.,
                    0.,
                    1.,
                ],
            );
        }
        let mut input = Vec::new();
        for mut p in states {
            p[14] = 6.;
            input.extend(p);
        }
        scene.sync(&ids, &input);
        for _ in 0..15 {
            scene.step(0.05);
        }
        let r = scene.read(&ids);
        let dot = (0..4).map(|i| r[18 + i] * r[33 + i]).sum::<f64>().abs();
        assert!(
            dot < 0.995,
            "full ragdoll arm locked: {dot}, angular {:?}",
            &r[40..43]
        );
    }
    /// (twist, swing) limits of head, arms and legs as NativeWorld.setupRigs sends them.
    const RAGDOLL_LIMITS: [[f64; 2]; 3] = [[0.9, 1.13], [0.9, 2.53], [0.5, 1.4]];
    /// Twist of `limb` about its own long axis relative to the torso, in radians.
    fn twist(r: &[f64], torso: usize, limb: usize) -> f64 {
        let q = |i: usize| pose(r, i * STRIDE).rotation;
        let rel = q(torso).inverse() * q(limb);
        let mut angle = 2. * rel.y.atan2(rel.w);
        if angle > std::f64::consts::PI {
            angle -= 2. * std::f64::consts::PI;
        }
        if angle < -std::f64::consts::PI {
            angle += 2. * std::f64::consts::PI;
        }
        angle
    }
    #[test]
    fn ragdoll_legs_do_not_spin_about_their_axis() {
        let mut scene = Scene::new(9.81);
        let offsets = [(0., 10.625), (0., 10.), (-0.375, 10.), (0.375, 10.), (-0.125, 9.25), (0.125, 9.25)];
        let mut ids = Vec::new();
        let mut input = Vec::new();
        for i in 0..6 {
            let mut p = state(offsets[i].0, offsets[i].1);
            let mut shape = cube();
            shape[7] = if i < 2 { 0.25 } else { 0.125 };
            shape[8] = if i == 0 { 0.25 } else { 0.375 };
            shape[9] = if i == 0 { 0.25 } else { 0.125 };
            if i == 4 {
                p[11] = 30.;
            }
            if i == 5 {
                p[10] = 30.;
            }
            if i == 3 {
                p[12] = 30.;
            }
            ids.push(scene.create(if i == 1 { 4. } else if i == 0 { 1.5 } else { 1. }, 1., 1, i as i32, &p, &shape));
            p[14] = 6.;
            input.extend(p);
        }
        // Same rig as NativeWorld.setupRigs.
        let (head, arm, leg) = (RAGDOLL_LIMITS[0], RAGDOLL_LIMITS[1], RAGDOLL_LIMITS[2]);
        scene.joint(ids[1], ids[0], &[0., 0., 0.375, 0., 0., -0.28, 0., head[0], 1., head[1], 0., 0., 0., 1., 0., 1.]);
        for i in 2..4 {
            let side = if i == 2 { -1. } else { 1. };
            scene.joint(ids[1], ids[i], &[0., side * 0.25, 0.3, 0., -side * 0.15, 0.3, 0., arm[0], 2., arm[1], 0., 0., 0., 1., 0., 1.]);
        }
        for i in 4..6 {
            scene.joint(ids[1], ids[i], &[0., if i == 4 { -0.14 } else { 0.14 }, -0.375, 0., 0., 0.405, 0., leg[0], 1.25, leg[1], 0., 0., 0., 1., 0., 1.]);
        }
        scene.sync(&ids, &input);
        let (mut worst, mut swing): (f64, f64) = (0., 0.);
        for _ in 0..40 {
            scene.step(0.05);
            let r = scene.read(&ids);
            // Arms swing near 180 degrees, where splitting swing from twist is meaningless; check the legs.
            for limb in 2..6 {
                if limb >= 4 {
                    worst = worst.max(twist(&r, 1, limb).abs() - RAGDOLL_LIMITS[2][0]);
                }
                let rel = pose(&r, STRIDE).rotation.inverse() * pose(&r, limb * STRIDE).rotation;
                swing = swing.max((rel * Vector::Y).angle_between(Vector::Y));
            }
        }
        assert!(swing < RAGDOLL_LIMITS[1][1] + 0.3, "ragdoll limb swung {swing} rad, past its cone");
        assert!(worst < 0.15, "ragdoll limb twisted {worst} rad past its limit about its own axis");
    }
    #[test]
    fn anchor_reaction_matches_suspended_weight() {
        let mut scene = Scene::new(9.81);
        let a = scene.create(0., 0., 1, -1, &state(0., 3.), &[]);
        let b = scene.create(2., 1., 2, -1, &state(0., 2.5), &cube());
        let joint = scene.joint(
            a,
            b,
            &[
                0., 0., 0., 0., 0., 0.5, 0., -1., -1., -1., 0., 0., 0., 1., 0., 0., 1.,
            ],
        );
        for _ in 0..100 {
            scene.step(0.05);
        }
        let force = scene.joint_force(joint);
        assert!(
            force[1] > 15. && force[1] < 25.,
            "Wrong anchor reaction: {force:?}"
        );
    }
    #[test]
    fn player_proxy_only_collides_with_chain_links() {
        let mut scene = Scene::new(0.);
        let shape = cube();
        let block = scene.create(1., 0., 1, -1, &state(0., 2.), &shape);
        scene.create(0., 0., 1, 10000, &state(0.6, 2.), &shape);
        let chain = scene.create(1., 0., 2, 200, &state(3., 2.), &shape);
        scene.create(0., 0., 2, 10000, &state(3.6, 2.), &shape);
        scene.step(0.05);
        let r = scene.read(&[block, chain]);
        assert!(r[0].abs() < 1e-8, "Player proxy moved standing support");
        assert!(r[15] < 2.99, "Player proxy passes through physical chain");
    }
    #[test]
    fn chain_never_pushes_its_own_holder() {
        let mut scene = Scene::new(0.);
        // A mob proxy (kinematic) and a ragdoll limb (dynamic) with a chain link stuck inside each.
        scene.create(0., 0., 7, -1, &state(0., 2.), &cube());
        let limb = scene.create(1., 0., 9, 1, &state(5., 2.), &cube());
        let a = scene.create(0.4, 0., 3, 200, &state(0.3, 2.), &cube());
        let b = scene.create(0.4, 0., 3, 201, &state(5.3, 2.), &cube());
        scene.apart = [owner_pair(3, 7), owner_pair(3, 9)].into_iter().collect();
        for _ in 0..5 {
            scene.step(0.05);
        }
        let r = scene.read(&[limb, a, b]);
        assert!((r[0] - 5.).abs() < 1e-6, "Chain pushed its ragdoll: {}", r[0]);
        assert!((r[15] - 0.3).abs() < 1e-6 && (r[30] - 5.3).abs() < 1e-6, "Holder pushed its chain");
        // Without the pair the same overlap separates the bodies.
        scene.apart.clear();
        scene.step(0.05);
        assert!(scene.read(&[a])[0] > 0.31, "Contact filter test is not exercising contacts");
    }
    #[test]
    fn rigid_rope_tows_heavy_body_without_growing() {
        let mut scene = Scene::new(0.);
        let mut a = state(0., 2.);
        a[7] = -5.;
        let first = scene.create(2000., 0., 1, -1, &a, &cube());
        let second = scene.create(5000., 0., 2, -1, &state(4., 2.), &cube());
        scene.joint(
            first,
            second,
            &[4., 0., 0., 0., 0., 0., 0., 4., 0., 0., 0., 0.],
        );
        for _ in 0..80 {
            scene.step(0.05);
            let r = scene.read(&[first, second]);
            assert!(r[15] - r[0] < 4.02, "Rigid chain stretches");
        }
        assert!(
            scene.read(&[second])[7] < -1.,
            "Second heavy body was not towed"
        );
    }
    /// Drops a 1-block cube into water whose surface is at y=5; returns the resting immersion depth.
    fn float(ratio: f64) -> f64 {
        let mut scene = Scene::new(9.81);
        let cube = scene.create(1., 1., 1, -1, &state(0., 8.), &cube());
        for _ in 0..300 {
            scene.water.insert(cube, vec![ratio, 1.5, 1.5, 5.]);
            scene.step(0.05);
        }
        5. - (scene.read(&[cube])[1] - 0.5)
    }
    #[test]
    fn buoyancy_floats_light_bodies_and_sinks_heavy_ones() {
        // Density 0.6 of water: 60% of a 1-block cube under the surface at rest.
        let light = float(1. / 0.6);
        assert!((light - 0.6).abs() < 0.05, "Floating depth {light}");
        let heavy = float(1. / 2.5);
        assert!(heavy > 3., "Heavy body did not sink: depth {heavy}");
    }
    #[test]
    fn buoyancy_lifts_articulated_ragdoll_limbs() {
        let mut scene = Scene::new(9.81);
        let torso = scene.create(4., 1., 1, 1, &state(0., 3.), &[0., 0., 0., 0., 0., 0., 1., 0.25, 0.375, 0.125, 0.6, 0.]);
        let arm = scene.create(1., 1., 1, 2, &state(-0.4, 3.), &[0., 0., 0., 0., 0., 0., 1., 0.125, 0.375, 0.125, 0.6, 0.]);
        scene.joint(torso, arm, &[0., -0.4, 0., 0., 0., 0., 0., 2.53, 2.0, 2.53, 0., 0., 0., 1., 0., 1.]);
        for _ in 0..200 {
            for id in [torso, arm] {
                scene.water.insert(id, vec![1. / 0.85, 1.5, 1.5, 5., 5.]);
            }
            scene.step(0.05);
        }
        let r = scene.read(&[torso, arm]);
        assert!(r[1] > 4. && r[16] > 4., "Ragdoll did not surface: {} {}", r[1], r[16]);
    }
    /// Chain between two kinematic anchors, laid out like PhysicalChainEntity; returns peak link speed after settling.
    fn two_point_chain(sagging: bool) -> (f64, f64) {
        let mut scene = Scene::new(9.81);
        let (n, span) = (8usize, 3.5);
        let half = span / n as f64 * 0.54;
        let a = scene.create(0., 0., 1, 208, &state(0., 10.), &[]);
        let b = scene.create(0., 0., 1, 209, &state(span, 10.), &[]);
        // Joint points: straight and 8% compressed (old), or a parabola with matching arc length.
        let mut sag = 0.;
        if sagging {
            let (mut lo, mut hi) = (0., span);
            for _ in 0..60 {
                let mid = (lo + hi) * 0.5;
                let len: f64 = (0..400)
                    .map(|i| {
                        let p = |t: f64| (span * t, -mid * 4. * t * (1. - t));
                        let (x0, y0) = p(i as f64 / 400.);
                        let (x1, y1) = p((i + 1) as f64 / 400.);
                        ((x1 - x0).powi(2) + (y1 - y0).powi(2)).sqrt()
                    })
                    .sum();
                if len < 2. * half * n as f64 { lo = mid } else { hi = mid }
            }
            sag = lo;
        }
        let point = |t: f64| (span * t, 10. - sag * 4. * t * (1. - t));
        let mut links = Vec::new();
        for i in 0..n {
            let (x0, y0) = point(i as f64 / n as f64);
            let (x1, y1) = point((i + 1) as f64 / n as f64);
            let angle = (x1 - x0).atan2(y1 - y0);
            let q = Rotation::from_axis_angle(Vector::new(0., 0., -1.), angle);
            let s = [(x0 + x1) * 0.5, (y0 + y1) * 0.5, 0., q.x, q.y, q.z, q.w, 0., 0., 0., 0., 0., 0., 1., 0.];
            let id = scene.create(0.4, 1., 1, 200 + i as i32, &s, &[0., 0., 0., 0., 0., 0., 1., 0.05, half, 0.05, 0.5, 0.]);
            let parent = links.last().copied().unwrap_or(a);
            // Same convention as NativeWorld.setupChains: previous link's +Y end to this link's -Y end.
            let top = if i == 0 { 0. } else { half };
            scene.joint(parent, id, &[0., 0., top, 0., 0., -half, 0., -1., -1., -1., 0., 10., 0., 1., 0., 0., 1., 1.]);
            links.push(id);
        }
        scene.joint(*links.last().unwrap(), b, &[0., 0., half, 0., 0., 0., 0., -1., -1., -1., 0., 10., 0., 1., 0., 0., 1., 1.]);
        let mut peak: (f64, f64) = (0., 0.);
        for frame in 0..160 {
            scene.step(0.05);
            for s in scene.read(&links).chunks_exact(15) {
                let v = (s[7] * s[7] + s[8] * s[8] + s[9] * s[9]).sqrt();
                let w = (s[10] * s[10] + s[11] * s[11] + s[12] * s[12]).sqrt();
                if frame >= 100 {
                    peak = (peak.0.max(v), peak.1.max(w));
                }
            }
        }
        peak
    }
    #[test]
    fn two_point_chain_rests_when_laid_out_with_sag() {
        let old = two_point_chain(false);
        let new = two_point_chain(true);
        eprintln!("compressed start {old:?}, sagging start {new:?}");
        assert!(new.0 < 0.1 && new.1 < 0.5, "Sagging chain keeps moving: {new:?}");
    }
    #[test]
    fn hanging_chain_settles_after_anchor_moves() {
        let mut scene = Scene::new(9.81);
        let anchor = scene.create(0., 0., 1, 208, &state(0., 10.), &[]);
        let link = [0., 0., 0., 0., 0., 0., 1., 0.05, 0.25, 0.05, 0.5, 0.];
        let mut links = Vec::new();
        for i in 0..8 {
            let id = scene.create(0.4, 1., 1, 200 + i, &state(0., 9.75 - 0.5 * i as f64), &link);
            let parent = links.last().copied().unwrap_or(anchor);
            let top = if i == 0 { 0. } else { -0.25 };
            scene.joint(
                parent,
                id,
                &[
                    0., 0., top, 0., 0., 0.25, 0., -1., -1., -1., 0., 10., 0., 1., 0., 0., 1., 1.,
                ],
            );
            links.push(id);
        }
        // Drag the kinematic anchor sideways like a moving attachment, then stop it.
        let mut energy = Vec::new();
        for frame in 0..200 {
            let mut s = state(0., 10.);
            s[7] = if frame < 20 { 4. } else { 0. };
            s[13] = 0.;
            s[14] = 6.;
            scene.sync(&[anchor], &s);
            scene.step(0.05);
            let r = scene.read(&links);
            let mut e = 0.;
            for s in r.chunks_exact(15) {
                e += s[7] * s[7] + s[8] * s[8] + s[9] * s[9];
            }
            energy.push(e);
        }
        let early: f64 = energy[40..60].iter().sum();
        let late: f64 = energy[180..200].iter().sum();
        eprintln!("chain energy {early} -> {late}");
        // Whole-chain pendulum sway is physical; the old tuning kept 55% with links whipping at 23 rad/s.
        assert!(late < early * 0.45, "Chain does not calm down: {early} -> {late}");
    }
}
