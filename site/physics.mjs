// The website's 2D sandbox: a small impulse-based rigid-body solver (oriented boxes, clipped contact manifolds,
// warm-started sequential impulses with friction), a soft mouse joint for grabbing, rocket aerodynamics, motors and
// explosions. Units are metres, kilograms and seconds, y points up. It is a toy for the browser, not the mod's engine.

export const GRAVITY = -9.81;

export const ROCKETS = Object.freeze({
  c75: { label: 'C-75', length: 3.7, wing: 1.05, mass: 120 },
  c25: { label: 'C-25', length: 3.2, wing: 0.92, mass: 95 },
  aim9x: { label: 'AIM-9X', length: 2.95, wing: 0.78, mass: 85 },
  rim7: { label: 'RIM-7', length: 2.9, wing: 0.96, mass: 90 },
  nine_k_119m: { label: '9K119M', length: 2.4, wing: 0.37, mass: 60 },
  kh101: { label: 'Kh-101', length: 3.8, wing: 1.42, mass: 130 },
  x25ml: { label: 'Kh-25ML', length: 3.0, wing: 0.81, mass: 95 },
  vihr: { label: 'Vikhr', length: 2.8, wing: 0.65, mass: 70 },
  tomahawk: { label: 'Tomahawk', length: 3.6, wing: 1.25, mass: 125 },
  s8: { label: 'S-8', length: 2.4, wing: 0.25, mass: 45 },
});

export const MATERIALS = Object.freeze({
  wood: { density: 70, friction: 0.65, bounce: 0.08 },
  stone: { density: 220, friction: 0.75, bounce: 0.04 },
  tnt: { density: 90, friction: 0.6, bounce: 0.06 },
  ground: { density: 0, friction: 0.85, bounce: 0.15 },
});

export const BURN_TIME = 2.4, THRUST = 22;
const ARM_SPEED = 7;
let LEAD = 1, FIN = 0.6, LOFT = 0.6;
/** Guidance gains, for tuning. */
export function tuneGuidance(g) { ({ lead: LEAD = LEAD, fin: FIN = FIN, loft: LOFT = LOFT } = g); }

let nextId = 1;

export class Body {
  constructor(x, y, hw, hh, mass, { angle = 0, friction = 0.6, bounce = 0.1, kind = 'box' } = {}) {
    Object.assign(this, { id: nextId++, x, y, a: angle, vx: 0, vy: 0, w: 0, fx: 0, fy: 0, t: 0, hw, hh, friction, bounce, kind, asleep: false, quiet: 0 });
    this.setMass(mass);
  }
  setMass(mass) {
    this.mass = mass;
    this.invM = mass > 0 ? 1 / mass : 0;
    this.I = mass > 0 ? mass * (4 * this.hw * this.hw + 4 * this.hh * this.hh) / 12 : 0;
    this.invI = this.I > 0 ? 1 / this.I : 0;
  }
  get radius() { return Math.hypot(this.hw, this.hh); }
  /** World point of a body-local point. */
  world(lx, ly) {
    const c = Math.cos(this.a), s = Math.sin(this.a);
    return [this.x + c * lx - s * ly, this.y + s * lx + c * ly];
  }
  /** Body-local point of a world point. */
  local(wx, wy) {
    const c = Math.cos(this.a), s = Math.sin(this.a), dx = wx - this.x, dy = wy - this.y;
    return [c * dx + s * dy, -s * dx + c * dy];
  }
  contains(wx, wy, pad = 0) {
    const [lx, ly] = this.local(wx, wy);
    return Math.abs(lx) <= this.hw + pad && Math.abs(ly) <= this.hh + pad;
  }
  wake() { this.asleep = false; this.quiet = 0; }
  impulse(px, py, wx, wy) {
    this.wake();
    this.vx += this.invM * px;
    this.vy += this.invM * py;
    this.w += this.invI * ((wx - this.x) * py - (wy - this.y) * px);
  }
  force(fx, fy, wx = this.x, wy = this.y) {
    this.fx += fx;
    this.fy += fy;
    this.t += (wx - this.x) * fy - (wy - this.y) * fx;
  }
}

export function makeBlock(material, x, y, size = 1) {
  const m = MATERIALS[material];
  const body = new Body(x, y, size / 2, size / 2, m.density * size * size, { friction: m.friction, bounce: m.bounce, kind: 'block' });
  body.material = material;
  if (material === 'tnt') body.fuse = -1;
  return body;
}

export function makeRocket(id, x, y, aspect = 0.2, angle = 0) {
  const spec = ROCKETS[id];
  if (!spec) throw new Error(`Unknown rocket: ${id}`);
  const hh = Math.max(0.16, spec.length * aspect / 2);
  const body = new Body(x, y, spec.length / 2, hh, spec.mass, { angle, friction: 0.45, bounce: 0.22, kind: 'rocket' });
  Object.assign(body, { rocket: id, burn: 0, armed: false });
  return body;
}

// --- Collision: two oriented boxes, Box2D-lite style face clipping. Normal points from a to b. ---

function incidentEdge(hw, hh, x, y, c, s, nx, ny) {
  // The normal in the incident box's frame, reversed.
  const lx = -(c * nx + s * ny), ly = -(-s * nx + c * ny);
  let v0, v1;
  if (Math.abs(lx) > Math.abs(ly)) {
    if (lx > 0) { v0 = [hw, -hh]; v1 = [hw, hh]; } else { v0 = [-hw, hh]; v1 = [-hw, -hh]; }
  } else if (ly > 0) { v0 = [hw, hh]; v1 = [-hw, hh]; } else { v0 = [-hw, -hh]; v1 = [hw, -hh]; }
  return [[x + c * v0[0] - s * v0[1], y + s * v0[0] + c * v0[1]], [x + c * v1[0] - s * v1[1], y + s * v1[0] + c * v1[1]]];
}

function clip(points, nx, ny, offset) {
  const out = [];
  const d0 = nx * points[0][0] + ny * points[0][1] - offset;
  const d1 = nx * points[1][0] + ny * points[1][1] - offset;
  if (d0 <= 0) out.push(points[0]);
  if (d1 <= 0) out.push(points[1]);
  if (d0 * d1 < 0) {
    const f = d0 / (d0 - d1);
    out.push([points[0][0] + f * (points[1][0] - points[0][0]), points[0][1] + f * (points[1][1] - points[0][1])]);
  }
  return out;
}

export function collide(A, B) {
  const ca = Math.cos(A.a), sa = Math.sin(A.a), cb = Math.cos(B.a), sb = Math.sin(B.a);
  const dx = B.x - A.x, dy = B.y - A.y;
  // Offset in each frame; C = RA^T RB.
  const dAx = ca * dx + sa * dy, dAy = -sa * dx + ca * dy;
  const dBx = cb * dx + sb * dy, dBy = -sb * dx + cb * dy;
  const c11 = Math.abs(ca * cb + sa * sb), c12 = Math.abs(-ca * sb + sa * cb);
  const c21 = Math.abs(-sa * cb + ca * sb), c22 = Math.abs(sa * sb + ca * cb);
  const faceAx = Math.abs(dAx) - A.hw - (c11 * B.hw + c12 * B.hh);
  const faceAy = Math.abs(dAy) - A.hh - (c21 * B.hw + c22 * B.hh);
  if (faceAx > 0 || faceAy > 0) return null;
  const faceBx = Math.abs(dBx) - (c11 * A.hw + c21 * A.hh) - B.hw;
  const faceBy = Math.abs(dBy) - (c12 * A.hw + c22 * A.hh) - B.hh;
  if (faceBx > 0 || faceBy > 0) return null;

  const rel = 0.95, abs = 0.01;
  let axis = 0, sep = faceAx;
  let nx = dAx > 0 ? ca : -ca, ny = dAx > 0 ? sa : -sa;
  if (faceAy > rel * sep + abs * A.hh) { axis = 1; sep = faceAy; nx = dAy > 0 ? -sa : sa; ny = dAy > 0 ? ca : -ca; }
  if (faceBx > rel * sep + abs * B.hw) { axis = 2; sep = faceBx; nx = dBx > 0 ? cb : -cb; ny = dBx > 0 ? sb : -sb; }
  if (faceBy > rel * sep + abs * B.hh) { axis = 3; sep = faceBy; nx = dBy > 0 ? -sb : sb; ny = dBy > 0 ? cb : -cb; }

  let front, sideX, sideY, neg, pos, edge;
  if (axis < 2) {
    const sx = axis === 0 ? -sa : ca, sy = axis === 0 ? ca : sa;
    const extent = axis === 0 ? A.hh : A.hw;
    front = nx * A.x + ny * A.y + (axis === 0 ? A.hw : A.hh);
    const side = sx * A.x + sy * A.y;
    [sideX, sideY, neg, pos] = [sx, sy, -side + extent, side + extent];
    edge = incidentEdge(B.hw, B.hh, B.x, B.y, cb, sb, nx, ny);
  } else {
    const sx = axis === 2 ? -sb : cb, sy = axis === 2 ? cb : sb;
    const extent = axis === 2 ? B.hh : B.hw;
    front = -nx * B.x - ny * B.y + (axis === 2 ? B.hw : B.hh);
    const side = sx * B.x + sy * B.y;
    [sideX, sideY, neg, pos] = [sx, sy, -side + extent, side + extent];
    edge = incidentEdge(A.hw, A.hh, A.x, A.y, ca, sa, -nx, -ny);
  }
  let pts = clip(edge, -sideX, -sideY, neg);
  if (pts.length < 2) return null;
  pts = clip(pts, sideX, sideY, pos);
  if (pts.length < 2) return null;

  // The reference face's outward normal: A's face points at B, B's face points at A.
  const fx = axis < 2 ? nx : -nx, fy = axis < 2 ? ny : -ny;
  const contacts = [];
  for (const p of pts) {
    const s = fx * p[0] + fy * p[1] - front;
    if (s <= 0) contacts.push({ x: p[0] - s * fx, y: p[1] - s * fy, sep: s, Pn: 0, Pt: 0 });
  }
  if (!contacts.length) return null;
  return { nx, ny, contacts };
}

// --- Contact constraints ---

class Arbiter {
  constructor(a, b) { this.a = a; this.b = b; this.contacts = []; this.nx = 0; this.ny = 1; }
  update(m) {
    // Keep accumulated impulses of contacts that stayed (matched by position) for warm starting.
    for (const c of m.contacts) {
      let best = null, bd = 0.0225;
      for (const o of this.contacts) {
        const d = (o.x - c.x) ** 2 + (o.y - c.y) ** 2;
        if (d < bd) { bd = d; best = o; }
      }
      if (best) { c.Pn = best.Pn; c.Pt = best.Pt; }
    }
    this.contacts = m.contacts;
    this.nx = m.nx; this.ny = m.ny;
  }
  preStep(invDt, world) {
    const { a, b, nx, ny } = this;
    const tx = ny, ty = -nx;
    this.friction = Math.sqrt(a.friction * b.friction);
    const e = Math.max(a.bounce, b.bounce);
    for (const c of this.contacts) {
      const rax = c.x - a.x, ray = c.y - a.y, rbx = c.x - b.x, rby = c.y - b.y;
      const rnA = rax * nx + ray * ny, rnB = rbx * nx + rby * ny;
      const kN = a.invM + b.invM + a.invI * (rax * rax + ray * ray - rnA * rnA) + b.invI * (rbx * rbx + rby * rby - rnB * rnB);
      c.mN = 1 / kN;
      const rtA = rax * tx + ray * ty, rtB = rbx * tx + rby * ty;
      const kT = a.invM + b.invM + a.invI * (rax * rax + ray * ray - rtA * rtA) + b.invI * (rbx * rbx + rby * rby - rtB * rtB);
      c.mT = 1 / kT;
      c.bias = -0.2 * invDt * Math.min(0, c.sep + 0.01);
      const dvx = b.vx - b.w * rby - a.vx + a.w * ray, dvy = b.vy + b.w * rbx - a.vy - a.w * rax;
      const vn = dvx * nx + dvy * ny;
      // Bounce only from a real impact: below 2 m/s a resting stack would feed its own jitter.
      if (vn < -2) c.bias = Math.max(c.bias, -e * (vn + 1));
      if (vn < -1.5) world.onImpact(a, b, -vn, c);
      // Warm start.
      const px = c.Pn * nx + c.Pt * tx, py = c.Pn * ny + c.Pt * ty;
      a.vx -= a.invM * px; a.vy -= a.invM * py; a.w -= a.invI * (rax * py - ray * px);
      b.vx += b.invM * px; b.vy += b.invM * py; b.w += b.invI * (rbx * py - rby * px);
    }
  }
  solve() {
    const { a, b, nx, ny } = this;
    const tx = ny, ty = -nx;
    for (const c of this.contacts) {
      const rax = c.x - a.x, ray = c.y - a.y, rbx = c.x - b.x, rby = c.y - b.y;
      let dvx = b.vx - b.w * rby - a.vx + a.w * ray, dvy = b.vy + b.w * rbx - a.vy - a.w * rax;
      let dPn = c.mN * (-(dvx * nx + dvy * ny) + c.bias);
      const Pn0 = c.Pn;
      c.Pn = Math.max(Pn0 + dPn, 0);
      dPn = c.Pn - Pn0;
      let px = dPn * nx, py = dPn * ny;
      a.vx -= a.invM * px; a.vy -= a.invM * py; a.w -= a.invI * (rax * py - ray * px);
      b.vx += b.invM * px; b.vy += b.invM * py; b.w += b.invI * (rbx * py - rby * px);

      dvx = b.vx - b.w * rby - a.vx + a.w * ray; dvy = b.vy + b.w * rbx - a.vy - a.w * rax;
      let dPt = c.mT * -(dvx * tx + dvy * ty);
      const max = this.friction * c.Pn, Pt0 = c.Pt;
      c.Pt = Math.max(-max, Math.min(Pt0 + dPt, max));
      dPt = c.Pt - Pt0;
      px = dPt * tx; py = dPt * ty;
      a.vx -= a.invM * px; a.vy -= a.invM * py; a.w -= a.invI * (rax * py - ray * px);
      b.vx += b.invM * px; b.vy += b.invM * py; b.w += b.invI * (rbx * py - rby * px);
    }
  }
}

// --- Grabbing: a soft spring-damper joint from a point on the body to the pointer ---

export class MouseJoint {
  constructor(body, wx, wy) {
    this.body = body;
    [this.lx, this.ly] = body.local(wx, wy);
    this.tx = wx; this.ty = wy;
    this.hz = 4.5; this.damping = 0.75;
    this.maxForce = 70 * body.mass * 9.81;
  }
  anchor() { return this.body.world(this.lx, this.ly); }
  preStep(dt) {
    const b = this.body, m = b.mass;
    const omega = 2 * Math.PI * this.hz;
    const d = 2 * m * this.damping * omega, k = m * omega * omega;
    let gamma = dt * (d + dt * k);
    gamma = gamma > 0 ? 1 / gamma : 0;
    const beta = dt * k * gamma;
    const [ax, ay] = this.anchor();
    this.rx = ax - b.x; this.ry = ay - b.y;
    const k11 = b.invM + b.invI * this.ry * this.ry + gamma, k12 = -b.invI * this.rx * this.ry, k22 = b.invM + b.invI * this.rx * this.rx + gamma;
    const det = 1 / (k11 * k22 - k12 * k12);
    this.m11 = det * k22; this.m12 = -det * k12; this.m22 = det * k11;
    this.cx = (ax - this.tx) * beta; this.cy = (ay - this.ty) * beta;
    this.gamma = gamma; this.px = 0; this.py = 0; this.maxImpulse = dt * this.maxForce;
    b.w *= 0.98;
  }
  solve() {
    const b = this.body;
    const vx = b.vx - b.w * this.ry + this.cx + this.gamma * this.px;
    const vy = b.vy + b.w * this.rx + this.cy + this.gamma * this.py;
    let ix = -(this.m11 * vx + this.m12 * vy), iy = -(this.m12 * vx + this.m22 * vy);
    const ox = this.px, oy = this.py;
    this.px += ix; this.py += iy;
    const len = Math.hypot(this.px, this.py);
    if (len > this.maxImpulse) { this.px *= this.maxImpulse / len; this.py *= this.maxImpulse / len; }
    ix = this.px - ox; iy = this.py - oy;
    b.vx += b.invM * ix; b.vy += b.invM * iy; b.w += b.invI * (this.rx * iy - this.ry * ix);
  }
}


// --- Destructible ground: a grid of one-metre cells under y = 0 ---

export const CELL = Object.freeze({ EMPTY: 0, DIRT: 1, STONE: 2, BEDROCK: 3 });

export class Terrain {
  constructor(x0 = -80, x1 = 130, depth = 12) {
    this.x0 = x0;
    this.cols = x1 - x0;
    this.depth = depth;
    this.cells = new Uint8Array(this.cols * depth);
    this.cache = new Map();
    this.version = 0;
    this.fill(x0, x1);
  }
  /** Puts the ground back between two x positions. */
  fill(from, to) {
    const i0 = Math.max(0, Math.floor(from) - this.x0), i1 = Math.min(this.cols, Math.ceil(to) - this.x0);
    for (let i = i0; i < i1; i++) for (let j = 0; j < this.depth; j++) {
      this.cells[i * this.depth + j] = j < 2 ? CELL.BEDROCK : j < this.depth - 5 ? CELL.STONE : CELL.DIRT;
    }
    this.version++;
  }
  /** Cell type at column i, row j (row depth-1 is the top layer, y in [-1, 0]). */
  at(i, j) {
    if (j < 0) return CELL.BEDROCK;
    if (j >= this.depth || i < 0 || i >= this.cols) return CELL.EMPTY;
    return this.cells[i * this.depth + j];
  }
  column(x) { return Math.floor(x) - this.x0; }
  row(y) { return Math.floor(y) + this.depth; }
  solidAt(x, y) { return this.at(this.column(x), this.row(y)) !== CELL.EMPTY; }
  /** Height of the ground's top under x. */
  surface(x) {
    const i = this.column(x);
    for (let j = this.depth - 1; j >= 0; j--) if (this.at(i, j)) return j - this.depth + 1;
    return -this.depth;
  }
  exposed(i, j) { return !this.at(i - 1, j) || !this.at(i + 1, j) || !this.at(i, j + 1) || !this.at(i, j - 1); }
  body(i, j) {
    const key = i * this.depth + j;
    let b = this.cache.get(key);
    if (!b) {
      b = new Body(this.x0 + i + 0.5, j - this.depth + 0.5, 0.5, 0.5, 0, { kind: 'cell', friction: MATERIALS.ground.friction, bounce: MATERIALS.ground.bounce });
      b.cell = key;
      this.cache.set(key, b);
    }
    return b;
  }
  /** Solid cells on the surface of the ground that a box around (x, y) of the given reach could touch. */
  near(x, y, reach, out) {
    out.length = 0;
    if (y - reach > 0.05) return out;
    const i0 = this.column(x - reach), i1 = this.column(x + reach);
    const j0 = Math.max(0, this.row(y - reach)), j1 = Math.min(this.depth - 1, this.row(y + reach));
    for (let i = i0; i <= i1; i++) for (let j = j0; j <= j1; j++) {
      if (this.at(i, j) && this.exposed(i, j)) out.push(this.body(i, j));
    }
    return out;
  }
  /** Blows a round hole; returns the removed cells (centre and type) for debris. */
  carve(x, y, r, keep = () => false) {
    const removed = [];
    const i0 = this.column(x - r), i1 = this.column(x + r), j0 = Math.max(0, this.row(y - r)), j1 = Math.min(this.depth - 1, this.row(y + r));
    for (let i = i0; i <= i1; i++) for (let j = j0; j <= j1; j++) {
      const type = this.at(i, j);
      if (!type || type === CELL.BEDROCK) continue;
      const cx = this.x0 + i + 0.5, cy = j - this.depth + 0.5;
      // A ragged edge: cells near the rim go only sometimes.
      const d = Math.hypot(cx - x, cy - y) / r;
      if (d > 1 || (d > 0.75 && Math.random() < 0.5) || keep(cx, cy)) continue;
      this.cells[i * this.depth + j] = CELL.EMPTY;
      removed.push({ x: cx, y: cy, type, key: i * this.depth + j });
    }
    if (removed.length) this.version++;
    return removed;
  }
}

// --- Aircraft ---

export function makePlane(team, x, y, dir = team === 'blue' ? 1 : -1) {
  const b = new Body(x, y, 3.1, 0.55, 2200, { angle: dir > 0 ? 0 : Math.PI, friction: 0.5, bounce: 0.1, kind: 'plane' });
  Object.assign(b, { team, dir, alive: true, hp: 100, cruise: 19 + Math.random() * 5, alt: y, cooldown: 2 + Math.random() * 4 });
  b.vx = dir * b.cruise;
  return b;
}

// --- The world ---

export class World {
  constructor(left = -70, right = 120) {
    this.bodies = [];
    this.arbiters = new Map();
    this.joint = null;
    this.events = [];
    this.iterations = 10;
    this.terrain = new Terrain(left - 10, right + 10);
    // Below the bedrock, in case anything ever gets through.
    this.ground = new Body(0, -this.terrain.depth - 50, 1e4, 50, 0, { friction: 0.8, kind: 'ground' });
    this.walls = [new Body(0, 0, 1, 500, 0, { kind: 'wall', friction: 0.4 }), new Body(0, 0, 1, 500, 0, { kind: 'wall', friction: 0.4 })];
    this.battle = { left: left + 35, right: right - 35 };
    this.protect = [];
    this.setBounds(left, right);
  }
  setBounds(left, right) {
    this.left = left; this.right = right;
    this.walls[0].x = left - 1;
    this.walls[1].x = right + 1;
  }
  add(body) { body.removed = false; this.bodies.push(body); return body; }
  remove(body) {
    body.removed = true;
    this.bodies = this.bodies.filter(b => b !== body);
    for (const [key, arb] of this.arbiters) if (arb.a === body || arb.b === body) this.arbiters.delete(key);
    if (this.joint?.body === body) this.joint = null;
  }
  clear() {
    for (const b of this.bodies) b.removed = true;
    this.bodies = []; this.arbiters.clear(); this.joint = null;
    this.terrain.fill(this.terrain.x0, this.terrain.x0 + this.terrain.cols);
  }
  bodyAt(x, y, pad = 0.12) {
    for (let i = this.bodies.length - 1; i >= 0; i--) if (this.bodies[i].contains(x, y, pad)) return this.bodies[i];
    return null;
  }
  grab(body, x, y) { body.wake(); this.joint = new MouseJoint(body, x, y); return this.joint; }
  release() { this.joint = null; }

  blast(body, radius = 5, power = 1) {
    if (body.dead) return;
    body.dead = true;
    this.events.push({ type: 'explode', x: body.x, y: body.y, radius, power, body });
  }

  onImpact(a, b, speed, contact) {
    for (const body of [a, b]) {
      if (body.kind === 'rocket' && body.armed && !(body.safe > 0) && speed > ARM_SPEED) this.blast(body);
      if (body.kind === 'block' && body.material === 'tnt' && body.fuse < 0 && speed > 14) body.fuse = 0.15;
      // A wreck hitting anything, or a flying plane flown into something hard, goes up.
      if (body.kind === 'plane' && (!body.alive ? speed > 3 : speed > 10)) this.blast(body, 6.5, 1.2);
    }
    if (speed > 3) this.events.push({ type: 'impact', x: contact.x, y: contact.y, speed, a, b });
  }

  explode(x, y, radius = 5, power = 1) {
    for (const b of this.bodies) {
      if (b.invM === 0 && !b.asleep) continue;
      const dx = b.x - x, dy = b.y - y, d = Math.max(0, Math.hypot(dx, dy) - Math.min(b.hw, 2));
      if (d > radius) continue;
      const falloff = 1 - d / radius;
      const mass = b.asleep ? b.saved?.[0] ? 1 / b.saved[0] : b.mass : b.mass;
      const j = power * 150 * falloff * Math.sqrt(mass) * (b.kind === 'plane' ? 0.35 : 1);
      const dist = Math.hypot(dx, dy);
      const ux = dist > 1e-3 ? dx / dist : 0, uy = dist > 1e-3 ? dy / dist : 1;
      // Hit slightly off-centre so things tumble.
      const ox = (Math.random() - 0.5) * b.hw, oy = (Math.random() - 0.5) * b.hh;
      b.impulse(ux * j, (uy + 0.35) * j, b.x + ox, b.y + oy);
      if (b.kind === 'block' && b.material === 'tnt' && b.fuse < 0) b.fuse = 0.18 + Math.random() * 0.25;
      if (b.kind === 'rocket') {
        if (b.armed && falloff > 0.35) this.blast(b);
        else if (!b.armed && falloff > 0.5) this.ignite(b);
      }
      if (b.kind === 'plane' && b.alive) {
        b.hp -= power * (150 * falloff + 20);
        if (b.hp <= 0) { b.alive = false; this.events.push({ type: 'down', x: b.x, y: b.y, body: b }); }
      }
    }
    // A blast near the ground digs a crater.
    if (y - radius * 0.45 * power < 0.2) {
      const removed = this.terrain.carve(x, y, radius * 0.45 * power, (cx, cy) => this.protect.some(p => Math.abs(cx - p.x) < p.r && cy > -2));
      if (removed.length) {
        const gone = new Set(removed.map(c => c.key));
        for (const [key, arb] of this.arbiters) if (gone.has(arb.a.cell) || gone.has(arb.b.cell)) this.arbiters.delete(key);
        for (const c of removed) this.terrain.cache.delete(c.key);
        for (const b of this.bodies) if (Math.hypot(b.x - x, b.y - y) < radius * 1.6) b.wake();
        this.events.push({ type: 'crater', x, y, cells: removed });
      }
    }
  }

  /** Lights a rocket's motor. target: nothing (straight), a point {x, y}, or a body to home on. */
  ignite(body, target = null, { burn = BURN_TIME, thrust = THRUST, team = null } = {}) {
    if (body?.kind !== 'rocket' || body.dead) return false;
    body.targetBody = target instanceof Body ? target : null;
    body.target = target instanceof Body ? null : target;
    body.team = team;
    body.wake();
    body.burn = burn;
    body.thrust = thrust;
    body.armed = true;
    // The fuse arms a moment after launch, clear of the rail and the ground beside it.
    body.safe = 0.3;
    return true;
  }

  aerodynamics(b) {
    const spec = ROCKETS[b.rocket];
    const c = Math.cos(b.a), s = Math.sin(b.a);
    const speed = Math.hypot(b.vx, b.vy);
    // Air at the centre of pressure, a third of the length behind the centre: the body's turning adds to it, which
    // damps the swing the same way the fins of a real rocket do.
    const rx = -0.6 * b.hw * c, ry = -0.6 * b.hw * s;
    const vcx = b.vx - b.w * ry, vcy = b.vy + b.w * rx;
    const along = b.vx * c + b.vy * s, across = -vcx * s + vcy * c;
    const m = b.mass;
    // Slender body: little axial drag, strong normal force from body and fins, acting behind the centre of mass so the
    // rocket turns its nose into the wind like an arrow.
    const axial = -0.012 * speed * along * m;
    const normal = -(0.03 + 0.08 * spec.wing) * speed * across * m;
    b.force(axial * c, axial * s);
    b.force(-normal * s, normal * c, b.x + rx, b.y + ry);
    b.t -= 0.3 * b.I * b.w;
    if (b.burn > 0) {
      const [tx, ty] = b.world(-b.hw, 0);
      const thrust = b.thrust ?? THRUST;
      b.force(thrust * m * c, thrust * m * s, tx, ty);
    }
    if (b.targetBody?.removed || (b.targetBody && b.targetBody.kind === 'rocket' && b.targetBody.dead)) b.targetBody = null;
    // Guided rockets keep steering after burnout while they still have speed for the fins to work with.
    if ((b.target || b.targetBody) && b.armed && speed > 4) {
      let want;
      if (b.targetBody) {
        // Lead a moving target: aim where it will be when we get there.
        const t = b.targetBody;
        const dist = Math.hypot(t.x - b.x, t.y - b.y);
        const tgo = Math.min(3, dist / Math.max(25, speed));
        want = Math.atan2(t.y + t.vy * tgo - b.y, t.x + t.vx * tgo - b.x);
      } else {
        // Loft over whatever is in the way while far, then dive onto the point.
        const dx = b.target.x - b.x, dy = b.target.y - b.y;
        want = Math.atan2(dy + LOFT * Math.max(0, Math.abs(dx) - 8), dx);
      }
      // Steer the flight path, not just the nose: point the nose past the wanted direction by as much as the velocity
      // still lags behind it.
      const course = Math.atan2(b.vy, b.vx);
      const lag = Math.atan2(Math.sin(want - course), Math.cos(want - course));
      const nose = want + Math.max(-0.6, Math.min(0.6, lag * LEAD));
      const error = Math.atan2(Math.sin(nose - b.a), Math.cos(nose - b.a));
      // Control fins are aerodynamic too: their authority grows with dynamic pressure, so they can hold an angle of
      // attack against the body's own weathervane pull, and that lift turns the flight path.
      const k = 12 + FIN * speed * speed;
      b.t += b.I * (k * Math.max(-0.7, Math.min(0.7, error)) - 1.6 * Math.sqrt(k) * b.w);
    }
  }

  fly(b) {
    const m = b.mass, speed = Math.hypot(b.vx, b.vy);
    if (!b.alive) {
      // A wreck: no lift worth the name, drag, and a slow roll toward the ground.
      b.force(-0.02 * speed * b.vx * m, -0.02 * speed * b.vy * m + 2 * m);
      b.t += b.I * 0.6 * Math.sign(Math.cos(b.a) || 1) * -1;
      return;
    }
    if (b.x > this.battle.right && b.dir > 0) b.dir = -1;
    if (b.x < this.battle.left && b.dir < 0) b.dir = 1;
    const course = Math.atan2(b.vy, b.vx);
    let vxWant, vyWant, limit = 14;
    if (Math.cos(course) * b.dir < 0.2) {
      // Turning back: a half loop at full speed (pull up and over), which leaves the plane upside down on the new
      // heading, then it rolls level.
      const turn = course + (b.dir < 0 ? 1 : -1) * 1.3;
      const v = Math.max(speed, b.cruise);
      vxWant = Math.cos(turn) * v; vyWant = Math.sin(turn) * v;
      limit = 48;
    } else {
      vxWant = b.dir * b.cruise;
      vyWant = Math.max(-6, Math.min(6, (b.alt - b.y) * 0.8)) + (b.evade ?? 0);
    }
    const ax = Math.max(-limit, Math.min(limit, (vxWant - b.vx) * 1.4));
    const ay = Math.max(-limit, Math.min(limit, (vyWant - b.vy) * 1.6)) - GRAVITY;
    b.force(m * ax, m * ay);
    // The nose follows the flight path.
    const error = Math.atan2(Math.sin(course - b.a), Math.cos(course - b.a));
    b.t += b.I * (30 * error - 9 * b.w);
  }

  step(dt) {
    if (dt <= 0) return;
    const invDt = 1 / dt;
    const bodies = this.bodies;
    const firstEvent = this.events.length;
    const cells = [];

    // Pairs.
    const seen = new Set();
    const check = (a, b) => {
      if (a.invM === 0 && b.invM === 0 && !a.asleep) return;
      if (b.kind === 'wall') {
        if (Math.abs(a.x - b.x) > a.radius + 1.05) return;
      } else if (b.kind === 'ground') {
        if (a.y - a.radius > b.y + b.hh + 0.05) return;
      } else {
        const r = a.radius + b.radius;
        if ((a.x - b.x) ** 2 + (a.y - b.y) ** 2 > r * r) return;
      }
      const key = a.id < b.id ? `${a.id}:${b.id}` : `${b.id}:${a.id}`;
      // Two sleeping bodies (or a sleeper on the ground) keep their contact as it was.
      if (a.asleep && (b.asleep || b.invM === 0) && this.arbiters.has(key)) { seen.add(key); return; }
      const m = collide(a, b);
      if (!m) return;
      seen.add(key);
      let arb = this.arbiters.get(key);
      if (!arb || arb.a !== a) { arb = new Arbiter(a, b); this.arbiters.set(key, arb); }
      arb.update(m);
    };
    for (let i = 0; i < bodies.length; i++) {
      const a = bodies[i];
      for (let j = i + 1; j < bodies.length; j++) check(a, bodies[j]);
      for (const cell of this.terrain.near(a.x, a.y, a.radius + 0.1, cells)) check(a, cell);
      check(a, this.ground);
      check(a, this.walls[0]);
      check(a, this.walls[1]);
    }
    for (const key of this.arbiters.keys()) if (!seen.has(key)) this.arbiters.delete(key);

    // Islands: bodies joined by contacts sleep and wake together, so a stack never leaves a block hanging in the air.
    if (this.joint) this.joint.body.wake();
    const links = new Map(bodies.map(b => [b, []]));
    for (const { a, b } of this.arbiters.values()) {
      if (a.invM > 0 || a.asleep) if (b.invM > 0 || b.asleep) { links.get(a)?.push(b); links.get(b)?.push(a); }
    }
    const visited = new Set();
    for (const start of bodies) {
      if (visited.has(start)) continue;
      const island = [start];
      visited.add(start);
      for (let i = 0; i < island.length; i++) {
        for (const other of links.get(island[i]) ?? []) if (!visited.has(other)) { visited.add(other); island.push(other); }
      }
      const awake = island.some(b => !b.asleep);
      const calm = island.every(b => b.quiet > 0.8);
      for (const b of island) {
        if (awake && !calm) b.asleep = false;
        else if (calm) b.asleep = true;
      }
    }
    // Sleepers stand still and count as fixed for this step.
    const sleepers = bodies.filter(b => b.asleep);
    for (const b of sleepers) { b.vx = b.vy = b.w = 0; b.saved = [b.invM, b.invI]; b.invM = b.invI = 0; }

    // Forces.
    for (const b of bodies) {
      if (b.asleep) { b.fx = b.fy = b.t = 0; continue; }
      if (b.kind === 'rocket') this.aerodynamics(b);
      else if (b.kind === 'plane') this.fly(b);
      else b.t -= 0.02 * b.I * b.w;
      b.vx += dt * b.invM * b.fx;
      b.vy += dt * (GRAVITY + b.invM * b.fy);
      b.w += dt * b.invI * b.t;
      // A little air for everything.
      const k = Math.exp(-0.02 * dt);
      b.vx *= k; b.vy *= k;
      b.fx = b.fy = b.t = 0;
    }

    // Contacts between two things that cannot move (sleepers, the ground) have nothing to solve.
    const active = [...this.arbiters.values()].filter(arb => arb.a.invM > 0 || arb.b.invM > 0);
    for (const arb of active) arb.preStep(invDt, this);
    this.joint?.preStep(dt);
    for (let i = 0; i < this.iterations; i++) {
      for (const arb of active) arb.solve();
      this.joint?.solve();
    }

    for (const b of sleepers) { [b.invM, b.invI] = b.saved; b.vx = b.vy = b.w = 0; }
    const lost = [];
    for (const b of bodies) {
      if (!b.asleep) {
        const still = b.vx * b.vx + b.vy * b.vy < 0.03 && Math.abs(b.w) < 0.25 && !(b.burn > 0) && !(b.fuse > 0) && this.joint?.body !== b && b.kind !== 'plane';
        b.quiet = still ? b.quiet + dt : 0;
      }
      b.x += dt * b.vx;
      b.y += dt * b.vy;
      b.a += dt * b.w;
      if (b.kind === 'rocket' && b.burn > 0) b.burn = Math.max(0, b.burn - dt);
      if (b.safe > 0) b.safe -= dt;
      // Proximity fuses: a point-guided rocket bursts where it was sent, a homing one next to its target.
      if (b.armed && !b.dead && !(b.safe > 0)) {
        if (b.target && Math.hypot(b.target.x - b.x, b.target.y - b.y) < 1.1) this.blast(b);
        else if (b.targetBody && Math.hypot(b.targetBody.x - b.x, b.targetBody.y - b.y) < (b.targetBody.kind === 'plane' ? 3 : 2.2)) this.blast(b);
      }
      if (b.kind === 'block' && b.fuse > 0) {
        b.fuse -= dt;
        if (b.fuse <= 0) this.blast(b, 6, 1.3);
      }
      if (b.kind === 'plane' && b.alive) b.cooldown -= dt;
      // Out of the world: gone, not teleported somewhere.
      if (b.y < -this.terrain.depth - 4 || b.y > 300 || b.x < this.left - 5 || b.x > this.right + 5 || !Number.isFinite(b.x + b.y + b.a)) lost.push(b);
    }
    for (const b of lost) { this.remove(b); this.events.push({ type: 'lost', body: b }); }

    // Explosions after the step, so the solver never sees a body vanish halfway. A blast can set off more blasts.
    for (let i = firstEvent; i < this.events.length; i++) {
      const e = this.events[i];
      if (e.type !== 'explode') continue;
      this.remove(e.body);
      this.explode(e.x, e.y, e.radius, e.power);
    }
  }

  /** Drains what happened since the last call (impacts, explosions, craters, planes going down) for the visuals. */
  drain() { const e = this.events; this.events = []; return e; }
}
