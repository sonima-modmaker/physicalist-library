//! Node-and-beam soft bodies in the spirit of BeamNG: point masses joined by damped springs, integrated with a small
//! fixed step (2000 Hz by default). Beams deform plastically and break, so a crash permanently bends the structure, and
//! pieces whose beams all broke fly off on their own. Collision (nodes against world boxes and against other bodies'
//! nodes) and aerodynamics (one-sided pressure on hull triangles) both use the current, deformed node positions.
use jni::{
    objects::{JClass, JDoubleArray, JIntArray},
    sys::{jboolean, jdouble, jdoubleArray, jint, jintArray, jlong},
    JNIEnv,
};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
};

type V = [f64; 3];

/// A quick hasher for small integer keys (grid cells): the default SipHash dominated the per-substep lookups.
#[derive(Default, Clone, Copy)]
struct Fast(u64);
impl std::hash::Hasher for Fast {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0.rotate_left(5) ^ b as u64).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
        }
    }
    fn write_i32(&mut self, i: i32) {
        self.0 = (self.0.rotate_left(5) ^ i as u32 as u64).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
    }
    fn write_u32(&mut self, i: u32) {
        self.write_i32(i as i32);
    }
}
type FastMap<K, T> = HashMap<K, T, std::hash::BuildHasherDefault<Fast>>;
#[inline]
fn add(a: V, b: V) -> V {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}
#[inline]
fn sub(a: V, b: V) -> V {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
#[inline]
fn mul(a: V, s: f64) -> V {
    [a[0] * s, a[1] * s, a[2] * s]
}
#[inline]
fn dot(a: V, b: V) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
#[inline]
fn cross(a: V, b: V) -> V {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}
#[inline]
fn len(a: V) -> f64 {
    dot(a, a).sqrt()
}

/// Beam kinds: a normal structural beam, a bounded beam that only pulls or pushes outside [min, max] (door stops),
/// and a latch, a normal beam that can be switched off (a door that is shut).
const NORMAL: u8 = 0;
const BOUNDED: u8 = 1;
const LATCH: u8 = 2;
#[allow(dead_code)]
const KINDS: [u8; 3] = [NORMAL, BOUNDED, LATCH];
pub const BEAM_STRIDE: usize = 9;
pub const AERO_STRIDE: usize = 11;
pub const NODE_STRIDE: usize = 14;
pub const ROTOR_STRIDE: usize = 24;
pub const WHEEL_STRIDE: usize = 28;

/// A road wheel on a soft chassis: a suspension strut pinned to the chassis (mount point, up and forward axes in the
/// frame of three chassis nodes), a ray down to the ground for the tyre's contact, a sprung and damped strut, and a
/// tyre whose grip (longitudinal and lateral, inside a friction ellipse) acts on the chassis nodes around the mount.
/// The wheel spins on its own inertia under drive and brake torque and the tyre's pull.
struct WheelUnit {
    frame: [usize; 3],
    mount: V,
    up: V,
    forward: V,
    radius: f64,
    rest: f64,
    travel: f64,
    k: f64,
    c: f64,
    inertia: f64,
    mu_long: f64,
    mu_lat: f64,
    nodes: Vec<usize>,
    /// Chassis mass carried by this wheel (for how hard the tyre can pull without overshooting).
    share: f64,
    steer: f64,
    drive: f64,
    brake: f64,
    /// Extra strut length asked for (ride height, hydraulics), metres.
    lift: f64,
    /// World forward and up of the wheel given from outside (the chassis' rigid frame), or zero to use the pinned frame.
    heading: V,
    heading_up: V,
    omega: f64,
    angle: f64,
    compression: f64,
    grounded: bool,
    load: f64,
    centre: V,
}

impl WheelUnit {
    fn step(&mut self, pos: &[V], vel: &[V], force: &mut [V], ground: &dyn Fn(V, V, f64) -> Option<(f64, V)>, dt: f64) {
        let (Some(mut up), Some(mut forward0), false) = (dir_in_frame(pos, self.frame, self.up), dir_in_frame(pos, self.frame, self.forward), self.nodes.is_empty()) else {
            return;
        };
        // A whole-chassis frame is steadier than three nodes that bend under the load (they would toe the wheel in).
        if len(self.heading) > 0.5 && len(self.heading_up) > 0.5 {
            forward0 = self.heading;
            up = self.heading_up;
        }
        let mount = in_frame(pos, self.frame, self.mount);
        // Steering turns the rolling direction about the strut.
        let (s, co) = self.steer.sin_cos();
        let side0 = cross(up, forward0);
        let forward = add(mul(forward0, co), mul(side0, s));
        let rest = self.rest + self.lift;
        let reach = rest + self.radius;
        let down = mul(up, -1.);
        let hit = ground(mount, down, reach + 0.05);
        let k = self.nodes.len() as f64;
        let v = mul(self.nodes.iter().fold([0.; 3], |a, &i| add(a, vel[i])), 1. / k);
        let previous = self.compression;
        let mut tyre = [0.; 3];
        let mut road_torque = 0.;
        match hit {
            Some((t, n)) if t - self.radius < rest => {
                let x = (rest - (t - self.radius)).min(self.travel * 1.5 + self.lift.max(0.));
                self.compression = x;
                self.grounded = true;
                let mut f = self.k * x + self.c * (x - previous) / dt;
                if x > self.travel + self.lift.max(0.) {
                    // Bump stop.
                    f += 12. * self.k * (x - self.travel - self.lift.max(0.));
                }
                let f = f.max(0.);
                self.load = f;
                let strut = mul(up, f);
                // Tyre frame on the ground.
                let fwd = sub(forward, mul(n, dot(forward, n)));
                let fl = len(fwd);
                if fl > 1e-6 {
                    let fwd = mul(fwd, 1. / fl);
                    let lat = cross(n, fwd);
                    let (v_long, v_lat) = (dot(v, fwd), dot(v, lat));
                    // Settle the slip within a few substeps, never faster than the tyre's grip allows.
                    let settle = 6. * dt;
                    let m = self.share.max(1.);
                    // A wheel the brake can hold this substep is part of the chassis: the tyre then stops the
                    // whole share of the car. Using the light wheel's own inertia there capped braking at a
                    // fraction of the grip and doubled stopping distances.
                    // Only real braking counts: a bearing's drag is no brake.
                    let held = self.brake > 0.05 * self.mu_long * f * self.radius && self.brake / self.inertia * dt >= self.omega.abs();
                    let reduced = if held { m } else { 1. / (1. / m + self.radius * self.radius / self.inertia) };
                    let mut long = reduced * (self.omega * self.radius - v_long) / settle;
                    let mut side = -m * v_lat / settle;
                    let (lmax, smax) = (self.mu_long * f, self.mu_lat * f);
                    let e = (long / lmax.max(1e-9)).powi(2) + (side / smax.max(1e-9)).powi(2);
                    if e > 1. {
                        let s = 1. / e.sqrt();
                        long *= s;
                        side *= s;
                    }
                    if held {
                        // A held wheel passes on at most what the brake can resist.
                        let cap = self.brake / self.radius;
                        long = long.clamp(-cap, cap);
                    }
                    tyre = add(mul(fwd, long), mul(lat, side));
                    road_torque = long * self.radius;
                }
                let push = mul(add(strut, tyre), 1. / k);
                for &i in &self.nodes {
                    force[i] = add(force[i], push);
                }
                self.centre = add(mount, mul(down, t - self.radius));
            }
            _ => {
                self.grounded = false;
                self.load = 0.;
                self.compression = (self.compression - 2. * dt).max(0.);
                self.centre = add(mount, mul(down, rest - self.compression));
            }
        }
        // The wheel's own spin: drive, the tyre pulling back, rolling resistance, brakes (never past standstill).
        let rolling = 0.008 * self.load * self.radius * self.omega.signum();
        self.omega += (self.drive - road_torque - rolling) / self.inertia * dt;
        let stop = self.brake / self.inertia * dt;
        self.omega = if self.omega.abs() <= stop { 0. } else { self.omega - stop * self.omega.signum() };
        // Traction control: a driven tyre on the ground turns at most a little faster than the road passes under it.
        if self.drive != 0. {
            // In the air a differential sends the torque elsewhere: the wheel does not run away either.
            let road = dot(v, forward);
            let allowed = road.abs() + (0.12 * road.abs()).max(0.8);
            let spin = self.omega * self.radius;
            if spin * self.drive.signum() > allowed.max(road * self.drive.signum() + 0.8) {
                self.omega = (road + self.drive.signum() * (0.12 * road.abs()).max(0.8)) / self.radius;
            }
        }
        self.angle = (self.angle + self.omega * dt) % (2. * std::f64::consts::PI);
    }
}

/// {a,b,c, mount, up, forward (in the a-b-c frame), radius, rest length, travel, spring, damper, inertia, grip along,
/// grip across, chassis mass share, node count, up to 6 nodes} = 28 values.
fn road_wheels(spec: &[f64], n: usize) -> Option<Vec<WheelUnit>> {
    let mut out = vec![];
    for c in spec.chunks_exact(WHEEL_STRIDE) {
        let frame = [c[0] as usize, c[1] as usize, c[2] as usize];
        let count = (c[21] as usize).min(6);
        let nodes: Vec<usize> = c[22..22 + count].iter().map(|&x| x as usize).collect();
        if frame.iter().chain(&nodes).any(|&i| i >= n) || c[12] <= 0. || c[17] <= 0. {
            return None;
        }
        out.push(WheelUnit {
            frame,
            mount: [c[3], c[4], c[5]],
            up: [c[6], c[7], c[8]],
            forward: [c[9], c[10], c[11]],
            radius: c[12],
            rest: c[13].max(0.),
            travel: c[14].max(0.01),
            k: c[15].max(0.),
            c: c[16].max(0.),
            inertia: c[17].max(1e-3),
            mu_long: c[18],
            mu_lat: c[19].max(0.),
            share: c[20].max(1.),
            nodes,
            steer: 0.,
            drive: 0.,
            brake: 0.,
            lift: 0.,
            heading: [0.; 3],
            heading_up: [0.; 3],
            omega: 0.,
            angle: 0.,
            compression: 0.,
            grounded: false,
            load: 0.,
            centre: [0.; 3],
        });
    }
    Some(out)
}

/// Distance along a ray (origin, unit dir, at most max) to the first terrain box it enters, and that face's normal.
fn ray_boxes(boxes: &[[f64; 6]], grid: &FastMap<(i32, i32, i32), Vec<u32>>, o: V, d: V, max: f64) -> Option<(f64, V)> {
    let e = add(o, mul(d, max));
    let mut best: Option<(f64, V)> = None;
    let mut seen: Vec<u32> = vec![];
    for x in o[0].min(e[0]).floor() as i32..=o[0].max(e[0]).floor() as i32 {
        for y in o[1].min(e[1]).floor() as i32..=o[1].max(e[1]).floor() as i32 {
            for z in o[2].min(e[2]).floor() as i32..=o[2].max(e[2]).floor() as i32 {
                let Some(list) = grid.get(&(x, y, z)) else { continue };
                for &bi in list {
                    if seen.contains(&bi) {
                        continue;
                    }
                    seen.push(bi);
                    let b = boxes[bi as usize];
                    let (mut t0, mut t1, mut normal) = (0f64, max, [0.; 3]);
                    let mut miss = false;
                    for a in 0..3 {
                        if d[a].abs() < 1e-12 {
                            if o[a] < b[a] || o[a] > b[a + 3] {
                                miss = true;
                                break;
                            }
                            continue;
                        }
                        let (mut ta, mut tb) = ((b[a] - o[a]) / d[a], (b[a + 3] - o[a]) / d[a]);
                        let mut nrm = [0.; 3];
                        nrm[a] = -d[a].signum();
                        if ta > tb {
                            std::mem::swap(&mut ta, &mut tb);
                        }
                        if ta > t0 {
                            t0 = ta;
                            normal = nrm;
                        }
                        t1 = t1.min(tb);
                        if t0 > t1 {
                            miss = true;
                            break;
                        }
                    }
                    // A ray starting inside a box (the wheel sunk into a step) counts as touching at once, pushing up.
                    if miss {
                        continue;
                    }
                    if normal == [0.; 3] {
                        normal = mul(d, -1.);
                    }
                    if best.map_or(true, |(t, _)| t0 < t) {
                        best = Some((t0, normal));
                    }
                }
            }
        }
    }
    best
}
const CHANNELS: usize = 8;

/// Where a point pinned to the triangle (a, b, c) is: a + u (b-a) + v (c-a) + w n / sqrt|n|, n = (b-a) x (c-a).
fn in_frame(pos: &[V], [a, b, c]: [usize; 3], f: V) -> V {
    let e1 = sub(pos[b], pos[a]);
    let e2 = sub(pos[c], pos[a]);
    let cr = cross(e1, e2);
    let l = len(cr);
    let s = if l < 1e-12 { 0. } else { 1. / l.sqrt() };
    add(pos[a], add(add(mul(e1, f[0]), mul(e2, f[1])), mul(cr, f[2] * s)))
}
/// A unit direction given in the same frame; None when the triangle collapsed.
fn dir_in_frame(pos: &[V], tri: [usize; 3], f: V) -> Option<V> {
    let d = sub(in_frame(pos, tri, f), pos[tri[0]]);
    let l = len(d);
    if l < 1e-12 {
        None
    } else {
        Some(mul(d, 1. / l))
    }
}

/// A propeller driven by an engine, computed blade element by blade element: each slice of each blade meets the air
/// at the angle its own speed (rotation plus the airframe's motion plus the air it pulls through the disc) makes
/// with its pitch, and its lift and drag give the thrust on the airframe and the torque the engine has to beat. The
/// air it pushes back (momentum theory) blows over whatever patches sit behind the disc.
struct Rotor {
    frame: [usize; 3],
    hub: V,
    axis: V,
    radius: f64,
    blades: f64,
    chord: f64,
    pitch: f64,
    inertia: f64,
    power: f64,
    max_omega: f64,
    nodes: Vec<usize>,
    omega: f64,
    angle: f64,
    induced: f64,
    thrust: f64,
    torque: f64,
    throttle: f64,
    on: bool,
    // Where it is this substep, for the slipstream.
    at: V,
    dir: V,
}

impl Rotor {
    const ELEMENTS: usize = 8;

    fn lift_drag(alpha: f64) -> (f64, f64) {
        let a = alpha.abs();
        let cl = if a <= 0.28 { 5.8 * a } else { (1.62 - 2. * (a - 0.28)).max(1.05 * (2. * a).sin().abs()) };
        let s = alpha.sin();
        (cl.copysign(alpha), 0.015 + 1.2 * s * s)
    }

    /// Advances the rotor by dt and adds its thrust and the engine's reaction torque to the hub nodes.
    fn step(&mut self, pos: &[V], vel: &[V], force: &mut [V], density: f64, wind: V, dt: f64) {
        let (Some(axis), true) = (dir_in_frame(pos, self.frame, self.axis), !self.nodes.is_empty()) else {
            self.thrust = 0.;
            return;
        };
        let hub = in_frame(pos, self.frame, self.hub);
        self.at = hub;
        self.dir = axis;
        let mean_v = mul(self.nodes.iter().fold([0.; 3], |s, &i| add(s, vel[i])), 1. / self.nodes.len() as f64);
        // Positive when the airframe moves forwards through the air (along the thrust).
        let forward = dot(sub(mean_v, wind), axis);
        let inflow = forward + self.induced;
        let r0 = 0.18 * self.radius;
        let dr = (self.radius - r0) / Self::ELEMENTS as f64;
        let (mut thrust, mut torque) = (0., 0.);
        if density > 0. {
            for e in 0..Self::ELEMENTS {
                let r = r0 + (e as f64 + 0.5) * dr;
                let tangential = self.omega * r;
                let w2 = inflow * inflow + tangential * tangential;
                if w2 < 1e-6 {
                    continue;
                }
                let phi = inflow.atan2(tangential);
                let theta = (self.pitch / (2. * std::f64::consts::PI * r)).atan();
                let (cl, cd) = Self::lift_drag(theta - phi);
                let chord = self.chord * (1.15 - 0.45 * (r / self.radius).powi(2));
                let q = 0.5 * density * w2 * chord * dr;
                let (s, c) = phi.sin_cos();
                thrust += q * (cl * c - cd * s);
                torque += r * q * (cl * s + cd * c);
            }
        }
        thrust *= self.blades;
        torque *= self.blades;
        let engine = if self.on && self.omega < self.max_omega {
            let open = 0.1 + 0.9 * self.throttle.clamp(0., 1.);
            open * self.power / self.omega.max(0.3 * self.max_omega)
        } else {
            0.
        };
        let friction = if self.omega > 0. { 0.04 * self.power / self.max_omega * (0.3 + self.omega / self.max_omega) } else { 0. };
        self.omega = (self.omega + (engine - torque - friction) / self.inertia * dt).max(0.);
        self.angle = (self.angle + self.omega * dt) % (2. * std::f64::consts::PI);
        // Momentum theory: the air speeds up through the disc by the induced velocity.
        let disc = std::f64::consts::PI * self.radius * self.radius;
        let target = if thrust > 0. && density > 0. {
            let v = forward.max(0.);
            0.5 * (-v + (v * v + 2. * thrust / (density * disc)).sqrt())
        } else {
            0.
        };
        self.induced += (target - self.induced) * (dt * 40.).min(1.);
        self.thrust = thrust;
        self.torque = engine;
        let k = self.nodes.len() as f64;
        let push = mul(axis, thrust / k);
        // The engine turns the propeller one way and the airframe the other: a couple on the hub nodes.
        let mut arms = Vec::with_capacity(self.nodes.len());
        let mut sum = 0.;
        for &i in &self.nodes {
            let d = sub(pos[i], hub);
            let r = sub(d, mul(axis, dot(d, axis)));
            sum += dot(r, r);
            arms.push(r);
        }
        let mut twist: Vec<V> = arms.iter().map(|r| if sum > 1e-9 { mul(cross(axis, *r), -engine / sum) } else { [0.; 3] }).collect();
        let net = mul(twist.iter().fold([0.; 3], |s, f| add(s, *f)), 1. / k);
        for f in &mut twist {
            *f = sub(*f, net);
        }
        for (n, &i) in self.nodes.iter().enumerate() {
            force[i] = add(force[i], add(push, twist[n]));
        }
    }

    /// Slipstream air speed (backwards, along -axis) at p: the induced speed at the disc, twice that a radius behind.
    fn wash(&self, p: V) -> V {
        if self.induced <= 0. {
            return [0.; 3];
        }
        let d = sub(p, self.at);
        let s = dot(d, self.dir);
        if s > 0.3 * self.radius || s < -12. * self.radius {
            return [0.; 3];
        }
        let radial = len(sub(d, mul(self.dir, s)));
        // The slipstream narrows as it speeds up: to 0.75 of the disc a radius behind.
        let behind = (-s / self.radius).clamp(0., 1.);
        let width = self.radius * (1. - 0.25 * behind);
        let edge = ((1.05 * width - radial) / (0.2 * width)).clamp(0., 1.);
        if edge <= 0. {
            return [0.; 3];
        }
        let grown = 1. + behind;
        mul(self.dir, -self.induced * grown * edge)
    }
}

#[derive(Clone)]
struct Beam {
    a: usize,
    b: usize,
    rest: f64,
    original: f64,
    k: f64,
    c: f64,
    /// Strain beyond which the rest length follows the beam (plastic deformation); 0 = never.
    deform: f64,
    /// Plastic elongation (fraction of the original length) at which the beam snaps; 0 = never.
    strength: f64,
    kind: u8,
    min: f64,
    max: f64,
    alive: bool,
    enabled: bool,
}

struct Aero {
    n: [usize; 3],
    /// Area per unit of |cross(b-a, c-a)|, and the patch normal in the frame (b-a, c-a, cross/sqrt|cross|).
    scale: f64,
    frame: V,
    /// Control surface channel (or none) and where the normal turns to when the surface deflects (same frame).
    channel: Option<usize>,
    tilt: V,
}

/// Shape matching (Mueller et al. 2005) over one piece of one group: every node is pulled towards where the piece's
/// best-fit rotation of its rest shape puts it. Thin parts (doors, wings) get the bending stiffness their two close
/// skins of beams cannot give them. The rest shape yields: a node pushed further than `yield_` off its goal keeps
/// the dent.
struct Cluster {
    nodes: Vec<usize>,
    rest: Vec<V>,
    q: [f64; 4],
}

fn quat_mat(q: [f64; 4]) -> [V; 3] {
    let [x, y, z, w] = q;
    [
        [1. - 2. * (y * y + z * z), 2. * (x * y + z * w), 2. * (x * z - y * w)],
        [2. * (x * y - z * w), 1. - 2. * (x * x + z * z), 2. * (y * z + x * w)],
        [2. * (x * z + y * w), 2. * (y * z - x * w), 1. - 2. * (x * x + y * y)],
    ]
}
fn quat_mul(a: [f64; 4], b: [f64; 4]) -> [f64; 4] {
    [
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ]
}
/// quat_mat gives the columns of R (column j = R e_j).
fn rot(q: [f64; 4], v: V) -> V {
    let m = quat_mat(q);
    add(add(mul(m[0], v[0]), mul(m[1], v[1])), mul(m[2], v[2]))
}
fn rot_t(q: [f64; 4], v: V) -> V {
    let m = quat_mat(q);
    [dot(m[0], v), dot(m[1], v), dot(m[2], v)]
}
/// Solves m x = b for a symmetric 3x3 m; zero when m is (nearly) singular.
fn solve3(m: [V; 3], b: V) -> V {
    let det = dot(m[0], cross(m[1], m[2]));
    if det.abs() < 1e-12 {
        return [0.; 3];
    }
    // Cramer's rule with the rows of m (m is symmetric, so rows = columns).
    [dot(b, cross(m[1], m[2])) / det, dot(m[0], cross(b, m[2])) / det, dot(m[0], cross(m[1], b)) / det]
}

/// Rotational part of A (given as columns), warm-started from q (Mueller et al. 2016).
fn extract_rotation(a: [V; 3], mut q: [f64; 4]) -> [f64; 4] {
    for _ in 0..3 {
        let r = quat_mat(q);
        let omega = add(add(cross(r[0], a[0]), cross(r[1], a[1])), cross(r[2], a[2]));
        let denom = (dot(r[0], a[0]) + dot(r[1], a[1]) + dot(r[2], a[2])).abs() + 1e-9;
        let omega = mul(omega, 1. / denom);
        let w = len(omega);
        if w < 1e-9 {
            break;
        }
        let axis = mul(omega, 1. / w);
        let (s, c) = (w * 0.5).sin_cos();
        q = quat_mul([axis[0] * s, axis[1] * s, axis[2] * s, c], q);
        let l = (q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).sqrt();
        q = [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
    }
    q
}

struct Body {
    pos: Vec<V>,
    vel: Vec<V>,
    force: Vec<V>,
    inv: Vec<f64>,
    beams: Vec<Beam>,
    aero: Vec<Aero>,
    radius: f64,
    friction: f64,
    drag: f64,
    /// Per node: its own ground friction (negative: the body's).
    node_friction: Vec<f64>,
    /// Wheel nodes: the triangle their axle is pinned to and the axle in its frame. They roll along the ground
    /// with `wheel_friction` and grip sideways with their friction.
    wheels: Vec<Option<([usize; 3], V)>>,
    wheel_friction: f64,
    /// How much of the slipstream the patches feel (1: all of it).
    wash_scale: f64,
    /// Scales all aerodynamic forces (a car's interior surfaces do not meet the air as an aircraft's skin does).
    aero_scale: f64,
    /// Last substep's total aerodynamic forces: pressure drag, lift, skin friction (diagnostics).
    probe: [V; 3],
    channels: [f64; CHANNELS],
    rotors: Vec<Rotor>,
    road_wheels: Vec<WheelUnit>,
    group: Vec<i32>,
    /// Shape groups after the first hard knock (per part, so pieces can tear off), and whether it came.
    crash_group: Vec<i32>,
    damaged: bool,
    /// Hinge and door-stop nodes are part of the structure but never touch anything.
    solid: Vec<bool>,
    clusters: Vec<Cluster>,
    yield_: f64,
    /// Per node: how much further than yield_ it may be pushed before it stays bent (landing gear is springy).
    node_yield: Vec<f64>,
    beta: f64,
    boxes: Vec<[f64; 6]>,
    grid: FastMap<(i32, i32, i32), Vec<u32>>,
    /// Nodes close enough to a terrain box to touch one during this step (checked once per step).
    near: Vec<bool>,
    any_near: bool,
    /// Aerodynamic forces, worked out every other substep and applied on both.
    aero_force: Vec<V>,
    terrain_hash: u64,
    components: Vec<u32>,
    components_dirty: bool,
    version: u32,
    broken: u32,
    quiet: u32,
    sleeping: bool,
    min: V,
    max: V,
}

impl Body {
    fn wake(&mut self) {
        self.sleeping = false;
        self.quiet = 0;
    }
    /// A teleport that also turns the body: every node turns by q about the pivot and then moves by the offset;
    /// speeds turn with it, and each piece's remembered orientation too, so its shape match starts from the new pose.
    fn transform(&mut self, pivot: V, q: [f64; 4], offset: V) {
        for p in &mut self.pos {
            *p = add(add(pivot, rot(q, sub(*p, pivot))), offset);
        }
        for v in &mut self.vel {
            *v = rot(q, *v);
        }
        for c in &mut self.clusters {
            c.q = quat_mul(q, c.q);
        }
        self.wake();
        self.bounds();
    }
    fn bounds(&mut self) {
        let mut lo = [f64::MAX; 3];
        let mut hi = [f64::MIN; 3];
        for p in &self.pos {
            for k in 0..3 {
                lo[k] = lo[k].min(p[k]);
                hi[k] = hi[k].max(p[k]);
            }
        }
        self.min = sub(lo, [self.radius; 3]);
        self.max = add(hi, [self.radius; 3]);
    }
    fn set_terrain(&mut self, boxes: &[f64]) {
        let mut hash: u64 = 1469598103934665603;
        for v in boxes {
            hash = (hash ^ v.to_bits()).wrapping_mul(1099511628211);
        }
        if hash == self.terrain_hash {
            return;
        }
        self.terrain_hash = hash;
        self.wake();
        self.boxes.clear();
        self.grid.clear();
        for chunk in boxes.chunks_exact(6) {
            let b = [chunk[0], chunk[1], chunk[2], chunk[3], chunk[4], chunk[5]];
            if !b.iter().all(|v| v.is_finite()) || b[0] > b[3] || b[1] > b[4] || b[2] > b[5] {
                continue;
            }
            let index = self.boxes.len() as u32;
            self.boxes.push(b);
            for x in b[0].floor() as i32..=(b[3].ceil() as i32 - 1).max(b[0].floor() as i32) {
                for y in b[1].floor() as i32..=(b[4].ceil() as i32 - 1).max(b[1].floor() as i32) {
                    for z in b[2].floor() as i32..=(b[5].ceil() as i32 - 1).max(b[2].floor() as i32) {
                        self.grid.entry((x, y, z)).or_default().push(index);
                    }
                }
            }
        }
    }
    /// Union-find over the live structural beams; bounded door stops do not hold pieces together, and a stop whose
    /// ends ended up in different pieces is removed.
    fn components(&mut self) {
        let n = self.pos.len();
        let mut parent: Vec<usize> = (0..n).collect();
        fn find(p: &mut [usize], mut i: usize) -> usize {
            while p[i] != i {
                p[i] = p[p[i]];
                i = p[i];
            }
            i
        }
        for b in &self.beams {
            if b.alive && b.kind != BOUNDED {
                let (x, y) = (find(&mut parent, b.a), find(&mut parent, b.b));
                if x != y {
                    parent[x] = y;
                }
            }
        }
        let mut ids = HashMap::new();
        self.components = (0..n)
            .map(|i| {
                let root = find(&mut parent, i);
                let next = ids.len() as u32;
                *ids.entry(root).or_insert(next)
            })
            .collect();
        for b in &mut self.beams {
            if b.alive && b.kind == BOUNDED && self.components[b.a] != self.components[b.b] {
                b.alive = false;
            }
        }
        self.components_dirty = false;
        // One shape-matching cluster per group and piece, its rest shape taken from how it is now.
        let mut keys: HashMap<(i32, u32), usize> = HashMap::new();
        let mut clusters: Vec<Cluster> = vec![];
        for i in 0..n {
            let group = if self.damaged { &self.crash_group } else { &self.group };
            if group[i] < 0 || self.inv[i] <= 0. {
                continue;
            }
            // 1000 * (body + 1) + door: a hinge node, held rigidly by both a body part and the door, so the door can
            // only turn about the line through its two hinge nodes.
            let g = group[i];
            let both = [g / 1000 - 1, g % 1000];
            let groups: &[i32] = if g >= 1000 { &both } else { std::slice::from_ref(&group[i]) };
            for &g in groups {
                let k = *keys.entry((g, self.components[i])).or_insert_with(|| {
                    clusters.push(Cluster { nodes: vec![], rest: vec![], q: [0., 0., 0., 1.] });
                    clusters.len() - 1
                });
                clusters[k].nodes.push(i);
            }
        }
        clusters.retain(|c| c.nodes.len() >= 3);
        for c in &mut clusters {
            let total: f64 = c.nodes.iter().map(|&i| 1. / self.inv[i]).sum();
            let centre = mul(c.nodes.iter().fold([0.; 3], |s, &i| add(s, mul(self.pos[i], 1. / self.inv[i]))), 1. / total);
            c.rest = c.nodes.iter().map(|&i| sub(self.pos[i], centre)).collect();
        }
        self.clusters = clusters;
    }

    /// Everything here is weighted by node mass, so that it is purely internal: no net force or torque on the piece
    /// (an engine nine times heavier than the skin must not drag the plane along).
    fn shape(&mut self, dt: f64) {
        if self.beta <= 0. {
            return;
        }
        let mut knocked = false;
        for c in &mut self.clusters {
            let total: f64 = c.nodes.iter().map(|&i| 1. / self.inv[i]).sum();
            let weighted = |v: &Vec<V>| mul(c.nodes.iter().fold([0.; 3], |s, &i| add(s, mul(v[i], 1. / self.inv[i]))), 1. / total);
            let centre = weighted(&self.pos);
            let mean_v = weighted(&self.vel);
            let mut a = [[0.; 3]; 3];
            for (k, &i) in c.nodes.iter().enumerate() {
                let p = mul(sub(self.pos[i], centre), 1. / self.inv[i]);
                let q = c.rest[k];
                for j in 0..3 {
                    a[j] = add(a[j], mul(p, q[j]));
                }
            }
            c.q = extract_rotation(a, c.q);
            let mut offs = Vec::with_capacity(c.nodes.len());
            let mut yielded = false;
            for (k, &i) in c.nodes.iter().enumerate() {
                let goal = add(centre, rot(c.q, c.rest[k]));
                let mut off = sub(goal, self.pos[i]);
                let distance = len(off);
                let limit = self.yield_ * self.node_yield[i];
                // Crushed by more than a centimetre in one substep: an impact of some 20 m/s.
                knocked |= distance - limit > 0.01;
                if distance > limit {
                    // Plastic: the rest shape follows the node, keeping only the yield distance of spring-back.
                    let keep = limit / distance;
                    let moved = mul(off, 1. - keep);
                    c.rest[k] = sub(c.rest[k], rot_t(c.q, moved));
                    off = mul(off, keep);
                    yielded = true;
                }
                offs.push(off);
            }
            if yielded {
                // Keep the rest shape centred, or the piece would push itself along.
                let mut mean = [0.; 3];
                for (k, &i) in c.nodes.iter().enumerate() {
                    mean = add(mean, mul(c.rest[k], 1. / self.inv[i] / total));
                }
                for q in &mut c.rest {
                    *q = sub(*q, mean);
                }
            }
            // Internal forces only: whatever does not cancel out (clipped offsets) is removed.
            let mut net = [0.; 3];
            for (k, &i) in c.nodes.iter().enumerate() {
                net = add(net, mul(offs[k], 1. / self.inv[i] / total));
            }
            // Damping acts on the deviation from the piece's rigid motion (translation and spin), never on the spin
            // itself: a door must keep swinging, a tumbling plane keep tumbling.
            let mut l = [0.; 3];
            let mut inertia = [[0.; 3]; 3];
            for &i in &c.nodes {
                let m = 1. / self.inv[i];
                let r = sub(self.pos[i], centre);
                l = add(l, mul(cross(r, sub(self.vel[i], mean_v)), m));
                let rr = dot(r, r);
                for x in 0..3 {
                    for y in 0..3 {
                        inertia[x][y] += m * (if x == y { rr } else { 0. } - r[x] * r[y]);
                    }
                }
            }
            let omega = solve3(inertia, l);
            for (k, &i) in c.nodes.iter().enumerate() {
                let m = 1. / self.inv[i];
                let k_s = self.beta * m / (dt * dt);
                let c_s = (2. * 0.25 * (k_s * m).sqrt()).min(0.3 * m / dt);
                let rigid = add(mean_v, cross(omega, sub(self.pos[i], centre)));
                let f = add(mul(sub(offs[k], net), k_s), mul(sub(rigid, self.vel[i]), c_s));
                self.force[i] = add(self.force[i], f);
            }
        }
        // A hard knock: from now on every part keeps its own shape, so a wing can be torn off.
        if knocked && !self.damaged && self.crash_group != self.group {
            // Right away, in the middle of the impact: by the end of the step it would be over.
            self.damaged = true;
            self.components();
        }
    }

    fn forces(&mut self, gravity: f64, density: f64, wind: V, dt: f64, aero_now: bool) {
        for (i, f) in self.force.iter_mut().enumerate() {
            *f = if self.inv[i] > 0. { [0., -gravity / self.inv[i], 0.] } else { [0.; 3] };
        }
        let mut snapped = false;
        for b in &mut self.beams {
            if !b.alive || !b.enabled {
                continue;
            }
            let d = sub(self.pos[b.b], self.pos[b.a]);
            let l = len(d);
            if l < 1e-9 {
                continue;
            }
            let n = mul(d, 1. / l);
            let stretch = match b.kind {
                BOUNDED => {
                    if l < b.min {
                        l - b.min
                    } else if l > b.max {
                        l - b.max
                    } else {
                        continue;
                    }
                }
                _ => {
                    if b.deform < 0. {
                        // Brittle: purely elastic up to -deform of strain, then it breaks (a wing's root).
                        if ((l - b.rest) / b.original).abs() > -b.deform {
                            b.alive = false;
                            snapped = true;
                            continue;
                        }
                    } else if b.deform > 0. {
                        let strain = (l - b.rest) / b.original;
                        if strain > b.deform {
                            b.rest = l - b.deform * b.original;
                        } else if strain < -b.deform {
                            // Crumpling stops at a tenth of the original length: past that the beam is solid again.
                            b.rest = (l + b.deform * b.original).max(b.original * 0.1);
                        }
                        if b.strength > 0. && b.rest > b.original * (1. + b.strength) {
                            b.alive = false;
                            snapped = true;
                            continue;
                        }
                    }
                    l - b.rest
                }
            };
            let relative = dot(sub(self.vel[b.b], self.vel[b.a]), n);
            let f = mul(n, b.k * stretch + b.c * relative);
            self.force[b.a] = add(self.force[b.a], f);
            self.force[b.b] = sub(self.force[b.b], f);
        }
        if snapped {
            self.broken += 1;
            self.components_dirty = true;
            self.version += 1;
        }
        for r in &mut self.rotors {
            r.step(&self.pos, &self.vel, &mut self.force, density, wind, dt);
        }
        if !self.road_wheels.is_empty() {
            let (boxes, grid) = (&self.boxes, &self.grid);
            let ground = |o: V, d: V, m: f64| ray_boxes(boxes, grid, o, d, m);
            for w in &mut self.road_wheels {
                w.step(&self.pos, &self.vel, &mut self.force, &ground, dt);
            }
        }
        if density <= 0. {
            return;
        }
        if !aero_now {
            for (f, a) in self.force.iter_mut().zip(&self.aero_force) {
                *f = add(*f, *a);
            }
            return;
        }
        for a in &mut self.aero_force {
            *a = [0.; 3];
        }
        let washing = self.wash_scale > 0. && self.rotors.iter().any(|r| r.induced > 0.);
        let mut probe = [[0.; 3]; 3];
        for t in &self.aero {
            let [a, b, c] = t.n;
            let cr = cross(sub(self.pos[b], self.pos[a]), sub(self.pos[c], self.pos[a]));
            let l = len(cr);
            if l < 1e-12 {
                continue;
            }
            let e1 = sub(self.pos[b], self.pos[a]);
            let e2 = sub(self.pos[c], self.pos[a]);
            let s = 1. / l.sqrt();
            let mut raw = add(add(mul(e1, t.frame[0]), mul(e2, t.frame[1])), mul(cr, t.frame[2] * s));
            if let Some(ch) = t.channel {
                // A deflected control surface: the patch turns about its hinge.
                let (sd, cd) = self.channels[ch].sin_cos();
                let tilt = add(add(mul(e1, t.tilt[0]), mul(e2, t.tilt[1])), mul(cr, t.tilt[2] * s));
                raw = add(mul(raw, cd), mul(tilt, sd * len(raw) / len(tilt).max(1e-12)));
            }
            let rl = len(raw);
            if rl < 1e-12 {
                continue;
            }
            let normal = mul(raw, 1. / rl);
            let area = t.scale * l;
            let mut air = wind;
            if washing {
                let centre = mul(add(add(self.pos[a], self.pos[b]), self.pos[c]), 1. / 3.);
                for r in &self.rotors {
                    air = add(air, mul(r.wash(centre), self.wash_scale));
                }
            }
            let u = sub(mul(add(add(self.vel[a], self.vel[b]), self.vel[c]), 1. / 3.), air);
            let speed2 = dot(u, u);
            if speed2 < 1e-8 {
                continue;
            }
            let speed = speed2.sqrt();
            let along = dot(u, normal);
            let q = 0.5 * density * speed2 * area * self.aero_scale;
            // Lift: both sides of every patch, each with half a thin plate's normal force (pi sin a, stalling at
            // 0.63), so a wing's two skins add up to a plate's 2 pi sin a. Only the part across the flow is kept as
            // lift; pressure drag comes from the faces the air runs into (a flat face 1.0). Leeward faces add none:
            // a streamlined hull's sloping skin no longer drags like a brick.
            let s = along / speed;
            let normal_force = mul(normal, -q * s.signum() * (std::f64::consts::PI * s.abs()).min(0.63));
            let flow = mul(u, 1. / speed);
            let lift = sub(normal_force, mul(flow, dot(normal_force, flow)));
            // s^4: a face square to the flow keeps all of it, a rounded nose (where the air speeds round) little.
            let pressure = if s > 0. { (s * s * s * s).min(1.) } else { 0. };
            let mut f = add(lift, mul(flow, -q * pressure));
            // Skin friction on every face.
            let tangential = sub(u, mul(normal, along));
            let skin = mul(tangential, -0.5 * density * area * self.drag * speed * self.aero_scale);
            probe[0] = add(probe[0], mul(flow, -q * pressure));
            probe[1] = add(probe[1], lift);
            probe[2] = add(probe[2], skin);
            f = add(f, skin);
            let share = mul(f, 1. / 3.);
            for &i in &t.n {
                self.aero_force[i] = add(self.aero_force[i], share);
            }
        }
        for (f, a) in self.force.iter_mut().zip(&self.aero_force) {
            *f = add(*f, *a);
        }
        self.probe = probe;
    }

    fn integrate(&mut self, dt: f64) {
        for i in 0..self.pos.len() {
            if self.inv[i] <= 0. {
                continue;
            }
            self.vel[i] = add(self.vel[i], mul(self.force[i], self.inv[i] * dt));
            self.pos[i] = add(self.pos[i], mul(self.vel[i], dt));
        }
    }

    /// Once per step: which nodes could reach a terrain box before the step is over.
    fn mark_near(&mut self, seconds: f64) {
        self.any_near = false;
        if self.boxes.is_empty() {
            return;
        }
        for i in 0..self.pos.len() {
            self.near[i] = false;
            if !self.solid[i] {
                continue;
            }
            let (p, v) = (self.pos[i], self.vel[i]);
            let margin = self.radius + len(v) * seconds + 0.3;
            'cells: for x in (p[0] - margin).floor() as i32..=(p[0] + margin).floor() as i32 {
                for y in (p[1] - margin).floor() as i32..=(p[1] + margin).floor() as i32 {
                    for z in (p[2] - margin).floor() as i32..=(p[2] + margin).floor() as i32 {
                        if self.grid.contains_key(&(x, y, z)) {
                            self.near[i] = true;
                            self.any_near = true;
                            break 'cells;
                        }
                    }
                }
            }
        }
    }

    fn terrain(&mut self) {
        if self.boxes.is_empty() || !self.any_near {
            return;
        }
        let r = self.radius;
        for i in 0..self.pos.len() {
            if !self.solid[i] || !self.near[i] {
                continue;
            }
            let p = self.pos[i];
            let (x0, y0, z0) = ((p[0] - r).floor() as i32, (p[1] - r).floor() as i32, (p[2] - r).floor() as i32);
            let (x1, y1, z1) = ((p[0] + r).floor() as i32, (p[1] + r).floor() as i32, (p[2] + r).floor() as i32);
            let mut push = [0.; 3];
            let mut normal_sum = [0.; 3];
            let mut touched = false;
            for x in x0..=x1 {
                for y in y0..=y1 {
                    for z in z0..=z1 {
                        let Some(list) = self.grid.get(&(x, y, z)) else { continue };
                        for &bi in list {
                            let b = self.boxes[bi as usize];
                            let q = [p[0].clamp(b[0], b[3]), p[1].clamp(b[1], b[4]), p[2].clamp(b[2], b[5])];
                            let d = sub(p, q);
                            let dist = len(d);
                            let (n, depth) = if dist > 1e-9 {
                                if dist >= r {
                                    continue;
                                }
                                (mul(d, 1. / dist), r - dist)
                            } else {
                                // Centre inside the box: leave through the nearest face.
                                let faces = [
                                    (p[0] - b[0], [-1., 0., 0.]),
                                    (b[3] - p[0], [1., 0., 0.]),
                                    (p[1] - b[1], [0., -1., 0.]),
                                    (b[4] - p[1], [0., 1., 0.]),
                                    (p[2] - b[2], [0., 0., -1.]),
                                    (b[5] - p[2], [0., 0., 1.]),
                                ];
                                let best = faces.iter().min_by(|a, b| a.0.total_cmp(&b.0)).unwrap();
                                (best.1, best.0 + r)
                            };
                            for k in 0..3 {
                                if n[k] > 0. {
                                    push[k] = f64::max(push[k], n[k] * depth);
                                } else if n[k] < 0. {
                                    push[k] = f64::min(push[k], n[k] * depth);
                                }
                            }
                            normal_sum = add(normal_sum, n);
                            touched = true;
                        }
                    }
                }
            }
            if !touched {
                continue;
            }
            self.pos[i] = add(p, push);
            let nl = len(normal_sum);
            if nl < 1e-9 {
                continue;
            }
            let n = mul(normal_sum, 1. / nl);
            let v = self.vel[i];
            let vn = dot(v, n);
            if vn < 0. {
                let vt = sub(v, mul(n, vn));
                let mu = if self.node_friction[i] >= 0. { self.node_friction[i] } else { self.friction };
                let slow = |vt: V, mu: f64| {
                    let tl = len(vt);
                    let stop = mu * -vn;
                    if tl <= stop {
                        [0.; 3]
                    } else {
                        mul(vt, 1. - stop / tl)
                    }
                };
                // A wheel rolls: little friction along its rolling direction, full grip sideways.
                let rolling = self.wheels[i].and_then(|(tri, f)| dir_in_frame(&self.pos, tri, f)).and_then(|axle| {
                    let r = cross(axle, n);
                    let l = len(r);
                    (l > 1e-6).then(|| mul(r, 1. / l))
                });
                self.vel[i] = match rolling {
                    Some(r) => {
                        let along = mul(r, dot(vt, r));
                        add(slow(along, self.wheel_friction), slow(sub(vt, along), mu))
                    }
                    None => slow(vt, mu),
                };
            }
        }
    }
}

struct World {
    bodies: HashMap<i64, Body>,
    next: i64,
}

/// Node-against-node contact between two different bodies: equal and opposite, split by inverse mass, inelastic.
fn collide_pair(a: &mut Body, b: &mut Body) {
    let r = a.radius + b.radius;
    let lo = [a.min[0].max(b.min[0]), a.min[1].max(b.min[1]), a.min[2].max(b.min[2])];
    let hi = [a.max[0].min(b.max[0]), a.max[1].min(b.max[1]), a.max[2].min(b.max[2])];
    if lo[0] > hi[0] || lo[1] > hi[1] || lo[2] > hi[2] {
        return;
    }
    let cell = r.max(1e-3);
    let key = |p: V| ((p[0] / cell).floor() as i32, (p[1] / cell).floor() as i32, (p[2] / cell).floor() as i32);
    let mut hash: FastMap<(i32, i32, i32), Vec<usize>> = FastMap::default();
    for (j, p) in b.pos.iter().enumerate() {
        if b.solid[j] && p[0] >= lo[0] - r && p[0] <= hi[0] + r && p[1] >= lo[1] - r && p[1] <= hi[1] + r && p[2] >= lo[2] - r && p[2] <= hi[2] + r {
            hash.entry(key(*p)).or_default().push(j);
        }
    }
    if hash.is_empty() {
        return;
    }
    let mut hit = false;
    for i in 0..a.pos.len() {
        let p = a.pos[i];
        if !a.solid[i] || p[0] < lo[0] - r || p[0] > hi[0] + r || p[1] < lo[1] - r || p[1] > hi[1] + r || p[2] < lo[2] - r || p[2] > hi[2] + r {
            continue;
        }
        let (cx, cy, cz) = key(p);
        for x in cx - 1..=cx + 1 {
            for y in cy - 1..=cy + 1 {
                for z in cz - 1..=cz + 1 {
                    let Some(list) = hash.get(&(x, y, z)) else { continue };
                    for &j in list {
                        let d = sub(a.pos[i], b.pos[j]);
                        let dist = len(d);
                        if dist >= r || dist < 1e-9 {
                            continue;
                        }
                        let (wa, wb) = (a.inv[i], b.inv[j]);
                        let w = wa + wb;
                        if w <= 0. {
                            continue;
                        }
                        let n = mul(d, 1. / dist);
                        let depth = r - dist;
                        a.pos[i] = add(a.pos[i], mul(n, depth * wa / w));
                        b.pos[j] = sub(b.pos[j], mul(n, depth * wb / w));
                        let closing = dot(sub(a.vel[i], b.vel[j]), n);
                        if closing < 0. {
                            let j_imp = -closing / w;
                            a.vel[i] = add(a.vel[i], mul(n, j_imp * wa));
                            b.vel[j] = sub(b.vel[j], mul(n, j_imp * wb));
                        }
                        hit = true;
                    }
                }
            }
        }
    }
    if hit {
        a.wake();
        b.wake();
    }
}

impl World {
    fn step(&mut self, seconds: f64, substeps: usize, gravity: f64, density: f64, wind: V) {
        let dt = seconds / substeps as f64;
        for b in self.bodies.values_mut().filter(|b| b.sleeping) {
            for r in &mut b.rotors {
                r.angle = (r.angle + r.omega * seconds) % (2. * std::f64::consts::PI);
            }
        }
        let mut ids: Vec<i64> = self.bodies.keys().copied().filter(|id| !self.bodies[id].sleeping).collect();
        ids.sort();
        if ids.is_empty() {
            return;
        }
        for id in &ids {
            self.bodies.get_mut(id).unwrap().mark_near(seconds);
        }
        let substep = |body: &mut Body, s: usize| {
            body.forces(gravity, density, wind, dt, s % 2 == 0);
            body.shape(dt);
            body.integrate(dt);
            body.terrain();
        };
        for s in 0..substeps {
            if ids.len() > 1 {
                // Bodies only meet every fourth substep, so in between each one runs on its own core.
                use rayon::prelude::*;
                let mut active: Vec<&mut Body> = self.bodies.iter_mut().filter(|(id, _)| ids.contains(id)).map(|(_, b)| b).collect();
                active.par_iter_mut().for_each(|b| substep(b, s));
            } else {
                substep(self.bodies.get_mut(&ids[0]).unwrap(), s);
            }
            // Bodies only meet each other every fourth substep (500 Hz): plenty for node spheres, a quarter of the cost.
            if s % 4 == 3 && self.bodies.len() > 1 {
                let all: Vec<i64> = {
                    let mut v: Vec<i64> = self.bodies.keys().copied().collect();
                    v.sort();
                    v
                };
                for id in &all {
                    self.bodies.get_mut(id).unwrap().bounds();
                }
                for x in 0..all.len() {
                    for y in x + 1..all.len() {
                        let (ia, ib) = (all[x], all[y]);
                        if self.bodies[&ia].sleeping && self.bodies[&ib].sleeping {
                            continue;
                        }
                        let mut first = self.bodies.remove(&ia).unwrap();
                        let second = self.bodies.get_mut(&ib).unwrap();
                        collide_pair(&mut first, second);
                        self.bodies.insert(ia, first);
                    }
                }
            }
        }
        for id in &ids {
            let body = self.bodies.get_mut(id).unwrap();
            if body.components_dirty {
                body.components();
            }
            body.bounds();
            let fastest = body.vel.iter().map(|v| dot(*v, *v)).fold(0., f64::max).sqrt();
            // An engine ticking over against the brakes lets the body rest (the propeller keeps turning).
            let parked = body.wheel_friction >= 0.5;
            let running = body.rotors.iter().any(|r| r.on && (r.throttle > 0.05 || !parked) || !r.on && r.omega > 0.5 && !parked)
                || body.road_wheels.iter().any(|w| w.drive.abs() > 1e-6 || w.omega.abs() > 0.05);
            body.quiet = if fastest < 0.04 && !running { body.quiet + 1 } else { 0 };
            if body.quiet > 40 {
                body.sleeping = true;
                for v in &mut body.vel {
                    *v = [0.; 3];
                }
            }
        }
    }
}

fn worlds() -> &'static Mutex<HashMap<i64, World>> {
    static W: OnceLock<Mutex<HashMap<i64, World>>> = OnceLock::new();
    W.get_or_init(|| Mutex::new(HashMap::new()))
}
static NEXT: AtomicI64 = AtomicI64::new(1);

fn build(nodes: &[f64], beams: &[f64], aero: &[f64], rotors: &[f64], road: &[f64], params: &[f64]) -> Option<Body> {
    if road.len() % WHEEL_STRIDE != 0 || !road.iter().all(|v| v.is_finite()) {
        return None;
    }
    if nodes.len() % NODE_STRIDE != 0
        || nodes.is_empty()
        || beams.len() % BEAM_STRIDE != 0
        || aero.len() % AERO_STRIDE != 0
        || rotors.len() % ROTOR_STRIDE != 0
        || params.len() < 5
    {
        return None;
    }
    if !nodes.iter().chain(beams).chain(aero).chain(rotors).chain(params).all(|v| v.is_finite()) {
        return None;
    }
    let n = nodes.len() / NODE_STRIDE;
    let pos: Vec<V> = nodes.chunks_exact(NODE_STRIDE).map(|c| [c[0], c[1], c[2]]).collect();
    let inv: Vec<f64> = nodes.chunks_exact(NODE_STRIDE).map(|c| if c[3] > 0. { 1. / c[3] } else { 0. }).collect();
    let group: Vec<i32> = nodes.chunks_exact(NODE_STRIDE).map(|c| c[4] as i32).collect();
    let crash_group: Vec<i32> = nodes.chunks_exact(NODE_STRIDE).map(|c| c[13] as i32).collect();
    let solid: Vec<bool> = nodes.chunks_exact(NODE_STRIDE).map(|c| c[5] > 0.).collect();
    let node_friction: Vec<f64> = nodes.chunks_exact(NODE_STRIDE).map(|c| c[6].min(4.)).collect();
    let node_yield: Vec<f64> = nodes.chunks_exact(NODE_STRIDE).map(|c| if c[12] > 0. { c[12].min(100.) } else { 1. }).collect();
    let mut wheels = Vec::with_capacity(n);
    for (i, c) in nodes.chunks_exact(NODE_STRIDE).enumerate() {
        let (b, cc) = (c[7], c[8]);
        wheels.push(if b >= 0. && cc >= 0. && (b as usize) < n && (cc as usize) < n { Some(([i, b as usize, cc as usize], [c[9], c[10], c[11]])) } else { None });
    }
    let mut spinning = Vec::with_capacity(rotors.len() / ROTOR_STRIDE);
    for c in rotors.chunks_exact(ROTOR_STRIDE) {
        let frame = [c[0] as usize, c[1] as usize, c[2] as usize];
        let count = (c[16] as usize).min(7);
        let list: Vec<usize> = c[17..17 + count].iter().map(|&x| x as usize).collect();
        if frame.iter().chain(&list).any(|&i| i >= n) || c[9] <= 0. || c[13] <= 0. || c[15] <= 0. {
            return None;
        }
        spinning.push(Rotor {
            frame,
            hub: [c[3], c[4], c[5]],
            axis: [c[6], c[7], c[8]],
            radius: c[9],
            blades: c[10].max(1.),
            chord: c[11],
            pitch: c[12],
            inertia: c[13],
            power: c[14].max(0.),
            max_omega: c[15],
            nodes: list,
            omega: 0.,
            angle: 0.,
            induced: 0.,
            thrust: 0.,
            torque: 0.,
            throttle: 0.,
            on: false,
            at: [0.; 3],
            dir: [0., 0., 1.],
        });
    }
    let mut list = Vec::with_capacity(beams.len() / BEAM_STRIDE);
    for c in beams.chunks_exact(BEAM_STRIDE) {
        let (a, b) = (c[0] as usize, c[1] as usize);
        if a >= n || b >= n || a == b {
            return None;
        }
        let kind = c[6] as u8;
        // A latch pulls its ends to the length it is given (0: together), not to the length they start at.
        let rest = if kind == LATCH { c[7].max(1e-4) } else { len(sub(pos[b], pos[a])).max(1e-4) };
        list.push(Beam { a, b, rest, original: rest, k: c[2], c: c[3], deform: c[4], strength: c[5], kind, min: c[7], max: c[8], alive: true, enabled: true });
    }
    let mut tris = Vec::with_capacity(aero.len() / AERO_STRIDE);
    for c in aero.chunks_exact(AERO_STRIDE) {
        let t = [c[0] as usize, c[1] as usize, c[2] as usize];
        if t.iter().any(|&i| i >= n) {
            return None;
        }
        let channel = if c[7] >= 0. && (c[7] as usize) < CHANNELS { Some(c[7] as usize) } else { None };
        tris.push(Aero { n: t, scale: c[3], frame: [c[4], c[5], c[6]], channel, tilt: [c[8], c[9], c[10]] });
    }
    let mut body = Body {
        vel: vec![[0.; 3]; n],
        force: vec![[0.; 3]; n],
        pos,
        inv,
        beams: list,
        aero: tris,
        radius: params[0].clamp(0.005, 1.),
        friction: params[1].clamp(0., 4.),
        drag: params[2].clamp(0., 1.),
        node_friction,
        wheels,
        wheel_friction: params.get(5).copied().unwrap_or(0.03).clamp(0., 4.),
        wash_scale: params.get(6).copied().unwrap_or(1.).clamp(0., 2.),
        aero_scale: params.get(7).copied().unwrap_or(1.).clamp(0., 4.),
        probe: [[0.; 3]; 3],
        channels: [0.; CHANNELS],
        rotors: spinning,
        road_wheels: road_wheels(road, nodes.len() / NODE_STRIDE)?,
        group,
        crash_group,
        damaged: false,
        solid,
        clusters: vec![],
        yield_: params[3].clamp(1e-4, 10.),
        node_yield,
        beta: params[4].clamp(0., 0.2),
        boxes: vec![],
        grid: FastMap::default(),
        near: vec![false; n],
        any_near: false,
        aero_force: vec![[0.; 3]; n],
        terrain_hash: 0,
        components: vec![],
        components_dirty: true,
        version: 0,
        broken: 0,
        quiet: 0,
        sleeping: false,
        min: [0.; 3],
        max: [0.; 3],
    };
    // Where a wheel holds the chassis is its stiffest part (axle, hub, frame rail): full braking and cornering loads
    // pass through those few nodes every second and must not bend them; a crash still can.
    // The whole wheel well around the strut, not only the nodes it pushes on: otherwise everyday driving slowly pushed
    // the arch up into the body.
    let mut stiff = vec![false; body.pos.len()];
    for w in &body.road_wheels {
        let own: Vec<usize> = w.nodes.iter().chain(w.frame.iter()).copied().collect();
        if own.is_empty() {
            continue;
        }
        let centre = mul(own.iter().fold([0.; 3], |a, &i| add(a, body.pos[i])), 1. / own.len() as f64);
        let r = own.iter().map(|&i| len(sub(body.pos[i], centre))).fold(0., f64::max).max(1e-3);
        for (i, p) in body.pos.iter().enumerate() {
            if len(sub(*p, centre)) <= 2. * r {
                stiff[i] = true;
            }
        }
        for i in own {
            stiff[i] = true;
        }
    }
    for (i, s) in stiff.iter().enumerate() {
        if *s {
            body.node_yield[i] = (body.node_yield[i].max(1.) * 20.).min(100.);
        }
    }
    // Its beams too: they stretch with the load and spring back instead of creeping.
    for b in &mut body.beams {
        if b.deform > 0. && (stiff[b.a] || stiff[b.b]) {
            b.deform = b.deform.max(0.1);
        }
    }
    body.components();
    body.bounds();
    Some(body)
}

fn doubles(e: &JNIEnv, a: &JDoubleArray) -> Vec<f64> {
    let n = e.get_array_length(a).unwrap_or(0) as usize;
    if n >= 8_000_000 {
        return vec![];
    }
    let mut v = vec![0.; n];
    if e.get_double_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v
}
fn ints(e: &JNIEnv, a: &JIntArray) -> Vec<i32> {
    let n = e.get_array_length(a).unwrap_or(0) as usize;
    if n >= 1_000_000 {
        return vec![];
    }
    let mut v = vec![0; n];
    if e.get_int_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v
}
fn guard<T: Default>(e: &mut JNIEnv, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            let _ = e.throw_new("java/lang/IllegalStateException", "Physicalist soft-body operation failed");
            T::default()
        }
    }
}
fn with_body<T: Default>(world: i64, body: i64, f: impl FnOnce(&mut Body) -> T) -> T {
    let mut w = worlds().lock().unwrap_or_else(|p| p.into_inner());
    match w.get_mut(&world).and_then(|w| w.bodies.get_mut(&body)) {
        Some(b) => f(b),
        None => T::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_abi0(_: JNIEnv, _: JClass) -> jint {
    4
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_createWorld0(mut e: JNIEnv, _: JClass) -> jlong {
    guard(&mut e, || {
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        worlds().lock().unwrap_or_else(|p| p.into_inner()).insert(id, World { bodies: HashMap::new(), next: 1 });
        id
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_destroyWorld0(mut e: JNIEnv, _: JClass, world: jlong) {
    guard(&mut e, || {
        worlds().lock().unwrap_or_else(|p| p.into_inner()).remove(&world);
    })
}
/// nodes {x,y,z,mass,shape group (-1: none),solid (1: collides),friction (-1: the body's),wheel frame b,c (-1: not a
/// wheel),axle in the (node,b,c) frame}, beams {a,b,k,c,deform,strength,kind,min,max}, aero {a,b,c,area scale,
/// normal in the a-b-c frame,control channel (-1: none),deflected direction in the same frame}, rotors {a,b,c,hub
/// and axis in their frame,radius,blades,chord,pitch,inertia,power,max omega,node count,up to 7 hub nodes}, params
/// {node radius, friction, skin drag, yield distance, shape stiffness, wheel rolling friction}. Returns 0 when the
/// description is invalid.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_add0(
    mut e: JNIEnv,
    _: JClass,
    world: jlong,
    nodes: JDoubleArray,
    beams: JDoubleArray,
    aero: JDoubleArray,
    rotors: JDoubleArray,
    wheels: JDoubleArray,
    params: JDoubleArray,
) -> jlong {
    let (n, b, a, r, p) = (doubles(&e, &nodes), doubles(&e, &beams), doubles(&e, &aero), doubles(&e, &rotors), doubles(&e, &params));
    let w = doubles(&e, &wheels);
    guard(&mut e, || {
        let Some(body) = build(&n, &b, &a, &r, &w, &p) else { return 0 };
        let mut w = worlds().lock().unwrap_or_else(|p| p.into_inner());
        let Some(w) = w.get_mut(&world) else { return 0 };
        let id = w.next;
        w.next += 1;
        w.bodies.insert(id, body);
        id
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_remove0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong) {
    guard(&mut e, || {
        if let Some(w) = worlds().lock().unwrap_or_else(|p| p.into_inner()).get_mut(&world) {
            w.bodies.remove(&body);
        }
    })
}
/// World collision boxes {minX,minY,minZ,maxX,maxY,maxZ} around the body; unchanged boxes are ignored cheaply.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_terrain0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, boxes: JDoubleArray) {
    let b = doubles(&e, &boxes);
    guard(&mut e, || with_body(world, body, |s| s.set_terrain(&b)))
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_step0(
    mut e: JNIEnv,
    _: JClass,
    world: jlong,
    seconds: jdouble,
    substeps: jint,
    gravity: jdouble,
    density: jdouble,
    wind: JDoubleArray,
) {
    let w = doubles(&e, &wind);
    guard(&mut e, || {
        assert!(seconds.is_finite() && seconds > 0. && seconds < 1. && substeps > 0 && substeps <= 2000);
        let wind = if w.len() >= 3 && w.iter().all(|v| v.is_finite()) { [w[0], w[1], w[2]] } else { [0.; 3] };
        if let Some(world) = worlds().lock().unwrap_or_else(|p| p.into_inner()).get_mut(&world) {
            world.step(seconds, substeps as usize, gravity, density, wind);
            for b in world.bodies.values() {
                assert!(b.pos.iter().all(|p| p.iter().all(|v| v.is_finite())), "non-finite soft body");
            }
        }
    })
}
/// {x,y,z per node..., structure version, broken beam count, sleeping}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_read0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong) -> jdoubleArray {
    let out = guard(&mut e, || {
        with_body(world, body, |b| {
            let mut v = Vec::with_capacity(b.pos.len() * 3 + 3);
            for p in &b.pos {
                v.extend_from_slice(p);
            }
            v.push(b.version as f64);
            v.push(b.broken as f64);
            v.push(if b.sleeping { 1. } else { 0. });
            v.push(b.rotors.len() as f64);
            for r in &b.rotors {
                v.extend_from_slice(&[r.angle, r.omega, r.thrust, r.torque, r.induced, r.throttle, if r.on { 1. } else { 0. }]);
            }
            v.push(b.road_wheels.len() as f64);
            for w in &b.road_wheels {
                v.extend_from_slice(&[w.angle, w.omega, w.compression, if w.grounded { 1. } else { 0. }, w.load, w.centre[0], w.centre[1], w.centre[2]]);
            }
            // Diagnostics: aerodynamic force totals, then the total of all external and internal node forces.
            for p in &b.probe {
                v.extend_from_slice(p);
            }
            let total = b.force.iter().fold([0.; 3], |s, f| add(s, *f));
            v.extend_from_slice(&total);
            v
        })
    });
    let a = e.new_double_array(out.len() as i32).unwrap();
    e.set_double_array_region(&a, 0, &out).unwrap();
    a.into_raw()
}
/// Piece index of every node: nodes that share an index are still held together by beams.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_components0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong) -> jintArray {
    let out: Vec<i32> = guard(&mut e, || {
        with_body(world, body, |b| {
            if b.components_dirty {
                b.components();
            }
            b.components.iter().map(|&c| c as i32).collect()
        })
    });
    let a = e.new_int_array(out.len() as i32).unwrap();
    e.set_int_array_region(&a, 0, &out).unwrap();
    a.into_raw()
}
/// Adds a velocity change to the listed nodes.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_push0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, nodes: JIntArray, dv: JDoubleArray) {
    let (n, v) = (ints(&e, &nodes), doubles(&e, &dv));
    guard(&mut e, || {
        if v.len() < 3 || !v.iter().all(|x| x.is_finite()) {
            return;
        }
        with_body(world, body, |b| {
            b.wake();
            for &i in &n {
                if let Some(vel) = b.vel.get_mut(i as usize) {
                    *vel = add(*vel, [v[0], v[1], v[2]]);
                }
            }
        })
    })
}
/// Sets every node's velocity to one value (spawn launch speed, a lift by the player).
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_velocity0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, v: JDoubleArray) {
    let v = doubles(&e, &v);
    guard(&mut e, || {
        if v.len() < 3 || !v.iter().all(|x| x.is_finite()) {
            return;
        }
        with_body(world, body, |b| {
            b.wake();
            for vel in &mut b.vel {
                *vel = [v[0], v[1], v[2]];
            }
        })
    })
}
/// Moves the whole body by an offset (teleport), keeping its shape and speed.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_transform0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, t: JDoubleArray) {
    let t = doubles(&e, &t);
    guard(&mut e, || {
        // {pivot xyz, quaternion xyzw, offset xyz}
        if t.len() < 10 || !t.iter().all(|x| x.is_finite()) {
            return;
        }
        let n = (t[3] * t[3] + t[4] * t[4] + t[5] * t[5] + t[6] * t[6]).sqrt();
        if n < 1e-9 {
            return;
        }
        let q = [t[3] / n, t[4] / n, t[5] / n, t[6] / n];
        with_body(world, body, |b| b.transform([t[0], t[1], t[2]], q, [t[7], t[8], t[9]]))
    })
}
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_move0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, d: JDoubleArray) {
    let d = doubles(&e, &d);
    guard(&mut e, || {
        if d.len() < 3 || !d.iter().all(|x| x.is_finite()) {
            return;
        }
        with_body(world, body, |b| {
            b.wake();
            for p in &mut b.pos {
                *p = add(*p, [d[0], d[1], d[2]]);
            }
            b.bounds();
        })
    })
}
/// Switches a latch beam on or off; returns whether it is on now.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_latch0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, beam: jint, on: jboolean) -> jboolean {
    guard(&mut e, || {
        with_body(world, body, |b| {
            b.wake();
            match b.beams.get_mut(beam as usize) {
                Some(beam) if beam.kind == LATCH && beam.alive => {
                    beam.enabled = on != 0;
                    if beam.enabled {
                        // Shut: the latch takes its length from where the door is now and then pulls it closed.
                        beam.rest = beam.original;
                    }
                    beam.enabled as jboolean
                }
                _ => 0,
            }
        })
    })
}
/// Pilot inputs: control surface deflections (radians, per channel), {throttle 0..1, engine on} per rotor, and the
/// wheels' rolling friction (brakes).
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_control0(
    mut e: JNIEnv,
    _: JClass,
    world: jlong,
    body: jlong,
    channels: JDoubleArray,
    rotors: JDoubleArray,
    wheel_friction: jdouble,
) {
    let (c, r) = (doubles(&e, &channels), doubles(&e, &rotors));
    guard(&mut e, || {
        if !c.iter().chain(&r).all(|x| x.is_finite()) || !wheel_friction.is_finite() {
            return;
        }
        with_body(world, body, |b| {
            let mut changed = (b.wheel_friction - wheel_friction).abs() > 1e-9;
            b.wheel_friction = wheel_friction.clamp(0., 4.);
            for (k, v) in c.iter().take(CHANNELS).enumerate() {
                let v = v.clamp(-1.2, 1.2);
                changed |= (b.channels[k] - v).abs() > 1e-9;
                b.channels[k] = v;
            }
            for (rotor, input) in b.rotors.iter_mut().zip(r.chunks_exact(2)) {
                let (t, on) = (input[0].clamp(0., 1.), input[1] > 0.5);
                changed |= (rotor.throttle - t).abs() > 1e-9 || rotor.on != on;
                rotor.throttle = t;
                rotor.on = on;
            }
            if changed {
                b.wake();
            }
        })
    })
}
/// Road wheel inputs: {steer (radians), drive torque, brake torque, strut lift (metres), spin or NaN, world forward xyz,
/// world up xyz (zeros: the pinned frame)} per wheel.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_wheels0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, input: JDoubleArray) {
    let v = doubles(&e, &input);
    guard(&mut e, || {
        if v.len() % 11 != 0 || !v.chunks_exact(11).all(|c| c[..4].iter().chain(&c[5..]).all(|x| x.is_finite())) {
            return;
        }
        with_body(world, body, |b| {
            let mut changed = false;
            // A strut changing length (ride height, a hop, a tyre going flat) moves the body even with the brakes on.
            let mut strut = false;
            for (w, c) in b.road_wheels.iter_mut().zip(v.chunks_exact(11)) {
                let (h, u) = ([c[5], c[6], c[7]], [c[8], c[9], c[10]]);
                w.heading = if len(h) > 0.5 { mul(h, 1. / len(h)) } else { [0.; 3] };
                w.heading_up = if len(u) > 0.5 { mul(u, 1. / len(u)) } else { [0.; 3] };
                // The fifth value, when a number, sets the wheel's spin (a vehicle reset from outside).
                if c[4].is_finite() {
                    w.omega = c[4];
                    changed = true;
                }
                let (s, d, k, l) = (c[0].clamp(-1.2, 1.2), c[1], c[2].max(0.), c[3].clamp(-0.5, 1.5));
                strut |= (w.lift - l).abs() > 1e-4;
                changed |= (w.steer - s).abs() > 1e-9 || (w.drive - d).abs() > 1e-9 || (w.brake - k).abs() > 1e-9 || (w.lift - l).abs() > 1e-9;
                w.lift = l;
                w.steer = s;
                w.drive = d;
                w.brake = k;
            }
            if strut || changed && b.road_wheels.iter().any(|w| w.drive != 0. || w.brake == 0.) {
                b.wake();
            }
        })
    })
}
/// Whether each beam still exists, all at once.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_beams0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong) -> jni::sys::jbooleanArray {
    let out: Vec<u8> = guard(&mut e, || with_body(world, body, |b| b.beams.iter().map(|x| x.alive as u8).collect()));
    let a = e.new_boolean_array(out.len() as i32).unwrap();
    e.set_boolean_array_region(&a, 0, &out).unwrap();
    a.into_raw()
}
/// Whether a beam still exists (doors whose hinge broke stop being doors).
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeSoft_alive0(mut e: JNIEnv, _: JClass, world: jlong, body: jlong, beam: jint) -> jboolean {
    guard(&mut e, || with_body(world, body, |b| b.beams.get(beam as usize).map_or(0, |x| x.alive as jboolean)))
}

#[cfg(test)]
mod tests {
    use super::*;
    /// A 2x2x2 lattice cube dropped on a floor: it lands, stays finite, and a hard drop bends it.
    fn cube(spacing: f64, height: f64) -> Body {
        let mut nodes = vec![];
        for x in 0..3 {
            for y in 0..3 {
                for z in 0..3 {
                    nodes.extend_from_slice(&[x as f64 * spacing, height + y as f64 * spacing, z as f64 * spacing, 10., 0., 1., -1., -1., -1., 0., 0., 0., 1., 0.]);
                }
            }
        }
        let n = nodes.len() / NODE_STRIDE;
        let s = NODE_STRIDE;
        let mut beams = vec![];
        for a in 0..n {
            for b in a + 1..n {
                let d = len(sub([nodes[b * s], nodes[b * s + 1], nodes[b * s + 2]], [nodes[a * s], nodes[a * s + 1], nodes[a * s + 2]]));
                if d < spacing * 1.8 {
                    let k = 0.5 * 10. / (0.0005f64.powi(2) * 26.);
                    beams.extend_from_slice(&[a as f64, b as f64, k, 2. * (k * 5.).sqrt() * 0.3, 0.01, 0.5, 0., 0., 0.]);
                }
            }
        }
        let mut body = build(&nodes, &beams, &[], &[], &[], &[0.1, 0.8, 0., 0.02, 0.03]).unwrap();
        body.set_terrain(&[-10., -1., -10., 10., 0., 10.]);
        body
    }
    /// Frame coordinates of p relative to nodes (a, b, c) of pos.
    fn frame_of(pos: &[V], a: usize, b: usize, c: usize, p: V) -> V {
        let (pa, pb, pc) = (pos[a], pos[b], pos[c]);
        let (e1, e2) = (sub(pb, pa), sub(pc, pa));
        let cr = cross(e1, e2);
        let e3 = mul(cr, 1. / len(cr).sqrt());
        let d = sub(p, pa);
        let det = dot(e1, cross(e2, e3));
        [dot(d, cross(e2, e3)) / det, dot(e1, cross(d, e3)) / det, dot(e1, cross(e2, d)) / det]
    }

    /// A lattice chassis on four road wheels: it settles on its springs, drives off under torque on the rear wheels
    /// (the wheels turning at road speed), turns with the front wheels steered, and stops on the brakes.
    #[test]
    fn car_on_road_wheels() {
        let (nx, ny, nz, s) = (3usize, 2usize, 5usize, 0.6);
        let idx = |x: usize, y: usize, z: usize| (x * ny + y) * nz + z;
        let mut nodes = vec![];
        for x in 0..nx {
            for y in 0..ny {
                for z in 0..nz {
                    nodes.extend_from_slice(&[x as f64 * s, 0.75 + y as f64 * s, z as f64 * s, 40., 0., 1., -1., -1., -1., 0., 0., 0., 1., 0.]);
                }
            }
        }
        let n = nodes.len() / NODE_STRIDE;
        let p: Vec<V> = (0..n).map(|i| [nodes[i * NODE_STRIDE], nodes[i * NODE_STRIDE + 1], nodes[i * NODE_STRIDE + 2]]).collect();
        let mut beams = vec![];
        for a in 0..n {
            for b in a + 1..n {
                if len(sub(p[a], p[b])) < s * 1.8 {
                    let k = 0.45 * 40. / (0.0005f64.powi(2) * 20.);
                    beams.extend_from_slice(&[a as f64, b as f64, k, 2. * (k * 20.).sqrt() * 0.2, 0., 0., 0., 0., 0.]);
                }
            }
        }
        let mut road = vec![];
        let corners = [(0, 0, true), (nx - 1, 0, true), (0, nz - 1, false), (nx - 1, nz - 1, false)];
        for &(x, z, rear) in &corners {
            let (a, b, c) = (idx(x, 0, z), idx(x, 1, z), idx(if x == 0 { 1 } else { x - 1 }, 0, z));
            let mount = p[a];
            let f = |q: V| frame_of(&p, a, b, c, q);
            let (fm, fu, ff) = (f(mount), f(add(mount, [0., 1., 0.])), f(add(mount, [0., 0., 1.])));
            let (fu, ff) = (sub(fu, fm), sub(ff, fm));
            let k = 300. * 9.81 / 0.08;
            let mut w = vec![a as f64, b as f64, c as f64, fm[0], fm[1], fm[2], fu[0], fu[1], fu[2], ff[0], ff[1], ff[2], 0.33, 0.35, 0.2, k, 0.8 * (k * 300.).sqrt(), 1.2, 1.0, 1.0, 300., 3.];
            let near = if z == 0 { idx(x, 0, 1) } else { idx(x, 0, z - 1) };
            w.extend_from_slice(&[a as f64, c as f64, near as f64, 0., 0., 0.]);
            road.extend_from_slice(&w);
            let _ = rear;
        }
        let mut body = build(&nodes, &beams, &[], &[], &road, &[0.1, 0.8, 0., 0.05, 0.03]).unwrap();
        let mut floor = vec![];
        for x in -20..20 {
            for z in -10..60 {
                floor.extend_from_slice(&[x as f64, -1., z as f64, x as f64 + 1., 0., z as f64 + 1.]);
            }
        }
        body.set_terrain(&floor);
        let mut w = World { bodies: HashMap::new(), next: 1 };
        w.bodies.insert(1, body);
        let control = |w: &mut World, steer: f64, drive: f64, brake: f64| {
            let b = w.bodies.get_mut(&1).unwrap();
            b.wake();
            for (i, wheel) in b.road_wheels.iter_mut().enumerate() {
                let rear = i < 2;
                wheel.steer = if rear { 0. } else { steer };
                wheel.drive = if rear { drive } else { 0. };
                wheel.brake = brake;
            }
        };
        let mean = |w: &World, f: &dyn Fn(V) -> f64| {
            let b = &w.bodies[&1];
            b.pos.iter().map(|&q| f(q)).sum::<f64>() / b.pos.len() as f64
        };
        let speed = |w: &World| {
            let b = &w.bodies[&1];
            mul(b.vel.iter().fold([0.; 3], |s, v| add(s, *v)), 1. / b.vel.len() as f64)
        };
        control(&mut w, 0., 0., 0.);
        for _ in 0..60 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let low = w.bodies[&1].pos.iter().map(|q| q[1]).fold(f64::MAX, f64::min);
        assert!(w.bodies[&1].road_wheels.iter().all(|x| x.grounded), "all wheels on the ground");
        assert!(low > 0.45 && low < 0.8, "chassis rides on its springs: bottom at {low}");
        control(&mut w, 0., 500., 0.);
        for _ in 0..60 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let v = speed(&w);
        let rear = &w.bodies[&1].road_wheels[0];
        assert!(v[2] > 4., "driven forwards: {:?}", v);
        assert!((rear.omega * rear.radius - v[2]).abs() < 1.5, "wheel turns at road speed: {} vs {}", rear.omega * rear.radius, v[2]);
        let x0 = mean(&w, &|q| q[0]);
        control(&mut w, 0.35, 300., 0.);
        for _ in 0..40 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let turned = mean(&w, &|q| q[0]) - x0;
        assert!(turned.abs() > 1., "steering turned the car: {turned}");
        control(&mut w, 0., 0., 4000.);
        for _ in 0..80 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let v = speed(&w);
        assert!(len(v) < 0.5, "brakes stop it: {:?}", v);
        let up = w.bodies[&1].pos[idx(0, 1, 0)][1] - w.bodies[&1].pos[idx(0, 0, 0)][1];
        assert!(up > 0.4, "still upright: {up}");
    }

    /// Timing of the parts of a substep on a plane-sized lattice (729 nodes, ~9000 beams): `cargo test --release
    /// bench_soft -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn bench_soft() {
        let (n1, s) = (9, 0.4);
        let mut nodes = vec![];
        for x in 0..n1 {
            for y in 0..n1 {
                for z in 0..n1 {
                    nodes.extend_from_slice(&[x as f64 * s, 0.5 + y as f64 * s, z as f64 * s, 1., (x / 3) as f64, 1., -1., -1., -1., 0., 0., 0., 1., (x / 3) as f64]);
                }
            }
        }
        let n = nodes.len() / NODE_STRIDE;
        let p = |i: usize| [nodes[i * NODE_STRIDE], nodes[i * NODE_STRIDE + 1], nodes[i * NODE_STRIDE + 2]];
        let mut beams = vec![];
        for a in 0..n {
            for b in a + 1..n {
                if len(sub(p(a), p(b))) < s * 1.8 {
                    let k = 0.45 / (0.0005f64.powi(2) * 18.);
                    beams.extend_from_slice(&[a as f64, b as f64, k, 2. * k.sqrt() * 0.2, 0.012, 0.45, 0., 0., 0.]);
                }
            }
        }
        let mut aero = vec![];
        for i in 0..n - n1 * n1 - 1 {
            aero.extend_from_slice(&[i as f64, (i + 1) as f64, (i + n1 * n1) as f64, 1., 0., 0., 1., -1., 0., 0., 0.]);
        }
        let mut body = build(&nodes, &beams, &aero, &[], &[], &[0.1, 0.8, 0.005, 0.05, 0.03]).unwrap();
        let mut floor = vec![];
        for x in -2..6 {
            for z in -2..6 {
                floor.extend_from_slice(&[x as f64, -1., z as f64, x as f64 + 1., 0., z as f64 + 1.]);
            }
        }
        body.set_terrain(&floor);
        for v in &mut body.vel {
            *v = [0., -3., 20.];
        }
        let dt = 0.0005;
        let t = std::time::Instant::now;
        let (mut tf, mut ts, mut ti, mut tt) = (0., 0., 0., 0.);
        body.mark_near(0.05);
        for k in 0..400 {
            let a = t();
            body.forces(9.81, 1.225, [0.; 3], dt, k % 2 == 0);
            let b = t();
            body.shape(dt);
            let c = t();
            body.integrate(dt);
            let d = t();
            body.terrain();
            let e = t();
            tf += (b - a).as_secs_f64();
            ts += (c - b).as_secs_f64();
            ti += (d - c).as_secs_f64();
            tt += (e - d).as_secs_f64();
        }
        let per = |x: f64| x / 4. * 1000.;
        println!("{} nodes {} beams {} clusters: per 100 substeps forces {:.2} ms shape {:.2} ms integrate {:.2} ms terrain {:.2} ms",
            n, body.beams.len(), body.clusters.len(), per(tf), per(ts), per(ti), per(tt));
    }
    /// A propeller on a lattice in the air: the engine spins it up, its blades pull the lattice along the axis, and
    /// the air it throws back is felt behind the disc but not in front of it.
    #[test]
    fn propeller_pulls_through_its_blades() {
        let mut body = cube(0.5, 50.);
        body.boxes.clear();
        body.grid.clear();
        // Hub at the lattice's front face centre (node 0-2-6-8 face is z = 0; z = 1 is the front), axis +z.
        let (a, b, c) = (2 * 9 + 1 * 3 + 2, 0 * 9 + 1 * 3 + 2, 1 * 9 + 2 * 3 + 2);
        let hub = [0.5, 50.5, 1.1];
        let axis_end = [0.5, 50.5, 2.1];
        let fa = |p: V| {
            // Coordinates of p in the (a, b, c) frame.
            let (pa, pb, pc) = (body.pos[a], body.pos[b], body.pos[c]);
            let (e1, e2) = (sub(pb, pa), sub(pc, pa));
            let cr = cross(e1, e2);
            let e3 = mul(cr, 1. / len(cr).sqrt());
            let d = sub(p, pa);
            let det = dot(e1, cross(e2, e3));
            [dot(d, cross(e2, e3)) / det, dot(e1, cross(d, e3)) / det, dot(e1, cross(e2, d)) / det]
        };
        let (h, tip) = (fa(hub), fa(axis_end));
        let dirf = [tip[0] - h[0], tip[1] - h[1], tip[2] - h[2]];
        let mut spec = vec![a as f64, b as f64, c as f64, h[0], h[1], h[2], dirf[0], dirf[1], dirf[2], 0.95, 2., 0.14, 1.55, 2.5, 120000., 263., 4.];
        spec.extend_from_slice(&[2., 8., 20., 26., 0., 0., 0.]);
        let mut nodes = vec![];
        for (i, p) in body.pos.iter().enumerate() {
            nodes.extend_from_slice(&[p[0], p[1], p[2], 1. / body.inv[i], 0., 1., -1., -1., -1., 0., 0., 0., 1., 0.]);
        }
        let beams: Vec<f64> = body.beams.iter().flat_map(|x| [x.a as f64, x.b as f64, x.k, x.c, x.deform, x.strength, 0., 0., 0.]).collect();
        let mut body = build(&nodes, &beams, &[], &spec, &[], &[0.1, 0.8, 0., 0.02, 0.03]).unwrap();
        body.rotors[0].on = true;
        body.rotors[0].throttle = 1.;
        let mut w = World { bodies: HashMap::new(), next: 1 };
        w.bodies.insert(1, body);
        for _ in 0..60 {
            w.step(0.05, 100, 0., 1.225, [0.; 3]);
        }
        let b = &w.bodies[&1];
        let r = &b.rotors[0];
        let v = mul(b.vel.iter().fold([0.; 3], |s, v| add(s, *v)), 1. / b.vel.len() as f64);
        assert!(r.omega > 150. && r.omega < 263., "omega {}", r.omega);
        assert!(r.thrust > 500., "thrust {}", r.thrust);
        assert!(v[2] > 5. && v[0].abs() < 1. && v[1].abs() < 1., "the propeller must pull along +z: {:?}", v);
        let behind = r.wash(add(r.at, mul(r.dir, -1.)));
        let ahead = r.wash(add(r.at, mul(r.dir, 1.)));
        assert!(dot(behind, r.dir) < -1. && len(ahead) == 0., "wash behind {:?} ahead {:?}", behind, ahead);
    }
    #[test]
    fn cube_lands_and_dents() {
        let mut w = World { bodies: HashMap::new(), next: 1 };
        w.bodies.insert(1, cube(0.5, 20.));
        for _ in 0..80 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let b = &w.bodies[&1];
        assert!(b.pos.iter().all(|p| p.iter().all(|v| v.is_finite())));
        let lowest = b.pos.iter().map(|p| p[1]).fold(f64::MAX, f64::min);
        assert!(lowest > 0.05 && lowest < 0.2, "lowest node {lowest}");
        let bent = b.beams.iter().filter(|x| (x.rest - x.original).abs() > 1e-3).count();
        assert!(bent > 0, "a 20 m drop must bend some beams");
    }
    #[test]
    fn rest_on_floor_is_still() {
        let mut w = World { bodies: HashMap::new(), next: 1 };
        w.bodies.insert(1, cube(0.5, 0.11));
        for _ in 0..200 {
            w.step(0.05, 100, 9.81, 1.225, [0.; 3]);
        }
        let b = &w.bodies[&1];
        let bent = b.beams.iter().filter(|x| (x.rest - x.original).abs() > 1e-3).count();
        assert!(bent == 0, "resting must not deform: {bent} beams bent");
        assert!(b.sleeping, "a resting body falls asleep");
    }
}
