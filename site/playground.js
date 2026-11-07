import { World, ROCKETS, makeBlock, makeRocket, BURN_TIME } from './physics.mjs';

// ---------------------------------------------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------------------------------------------

const stage = document.querySelector('#world-stage');
const canvas = document.querySelector('#rocket-canvas');
const ctx = canvas.getContext('2d');
const ammoButtons = [...document.querySelectorAll('[data-rocket]')];
const slowButton = document.querySelector('#slow-motion');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

const STEP = 1 / 120;
const FIELD = 30;          // metres of play field the camera frames at rest
const LAUNCHER = { x: 2.2, y: 0.9 };
const FORT_X = 19;

const world = new World(-70, FIELD + 70);
world.home = { x: 12, y: 12 };

const sprites = new Map();
let W = 1, H = 1, ratio = 1;
let ammo = 'kh101';
let slow = false;
let pointer = { x: 0, y: 0, sx: -1, sy: -1, inside: false, overBody: null };
let lastTouched = null;
let lastTap = { time: 0, body: null };
let cooldown = 0;
let shake = 0;
let flash = 0;
let clock = 0;
const particles = [];
const rings = [];
const lights = [];

const camera = { x: FIELD / 2, y: 6, zoom: 1, targetZoom: 1, hold: 0 };

// ---------------------------------------------------------------------------------------------------------------
// Pixel textures, made here so nothing is copied from the game
// ---------------------------------------------------------------------------------------------------------------

function seeded(seed) {
  return () => { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; };
}

function texture(paint, size = 16) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  paint(g, size);
  return c;
}

function noiseFill(g, size, colors, rand) {
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    g.fillStyle = colors[Math.floor(rand() * colors.length)];
    g.fillRect(x, y, 1, 1);
  }
}

const TEX = {
  grass: texture(g => {
    const r = seeded(7);
    noiseFill(g, 16, ['#8a5a36', '#7a4e2e', '#94643c', '#6d4429'], r);
    for (let x = 0; x < 16; x++) {
      const depth = 3 + Math.floor(r() * 3);
      for (let y = 0; y < depth; y++) {
        g.fillStyle = ['#62b346', '#58a43e', '#6fc04f', '#4f9637'][Math.floor(r() * 4)];
        g.fillRect(x, y, 1, 1);
      }
    }
  }),
  dirt: texture(g => noiseFill(g, 16, ['#7a4e2e', '#6d4429', '#86563a', '#5f3b24'], seeded(11))),
  wood: texture(g => {
    const r = seeded(3);
    noiseFill(g, 16, ['#b98a52', '#ad7f4b', '#c29459', '#a87a46'], r);
    g.fillStyle = '#7b5530';
    for (const y of [3, 7, 11, 15]) g.fillRect(0, y, 16, 1);
    for (const [x, y] of [[5, 0], [12, 4], [2, 8], [9, 12]]) g.fillRect(x, y, 1, 3);
  }),
  stone: texture(g => {
    const r = seeded(5);
    noiseFill(g, 16, ['#8d8d8f', '#838386', '#979799', '#7a7a7d'], r);
    g.fillStyle = '#5f5f63';
    for (const [x, y, w, h] of [[0, 4, 7, 1], [6, 0, 1, 5], [9, 7, 7, 1], [9, 2, 1, 6], [0, 11, 10, 1], [4, 11, 1, 5], [12, 11, 1, 5], [12, 12, 4, 1]]) g.fillRect(x, y, w, h);
    g.fillStyle = '#a9a9ab';
    for (const [x, y] of [[2, 1], [11, 4], [6, 8], [14, 14], [1, 13]]) g.fillRect(x, y, 2, 1);
  }),
  tnt: texture(g => {
    const r = seeded(9);
    noiseFill(g, 16, ['#d8402f', '#c93a2a', '#e04b35'], r);
    g.fillStyle = '#9c2a1e';
    for (const x of [3, 7, 11, 15]) g.fillRect(x, 0, 1, 16);
    g.fillStyle = '#efe9dc';
    g.fillRect(0, 5, 16, 6);
    g.fillStyle = '#2b2b2b';
    // T N T in a 3x4 pixel font.
    const glyphs = { T: ['111', '010', '010', '010'], N: ['101', '111', '111', '101'] };
    [...'TNT'].forEach((ch, i) => glyphs[ch].forEach((row, y) => [...row].forEach((on, x) => { if (on === '1') g.fillRect(2 + i * 4 + x, 6 + y, 1, 1); })));
  }),
  launcher: texture(g => {
    noiseFill(g, 16, ['#3b4552', '#36404c', '#414c5a'], seeded(13));
    g.fillStyle = '#596779';
    g.fillRect(0, 0, 16, 2);
    g.fillStyle = '#e2b13c';
    for (let x = 0; x < 16; x += 4) g.fillRect(x, 13, 2, 3);
  }),
};

const BLOCK_COLORS = { wood: ['#b98a52', '#7b5530', '#c29459'], stone: ['#8d8d8f', '#5f5f63', '#a9a9ab'], tnt: ['#d8402f', '#efe9dc', '#9c2a1e'] };

// ---------------------------------------------------------------------------------------------------------------
// Scene
// ---------------------------------------------------------------------------------------------------------------

function sleepAll(bodies) { for (const b of bodies) { b.asleep = true; b.quiet = 1; } }

function buildFort(x0 = FORT_X) {
  for (const b of [...world.bodies]) if (b.kind === 'block') world.remove(b);
  const blocks = [];
  const put = (material, x, y) => blocks.push(world.add(makeBlock(material, x0 + x + 0.5, y * 1.0005 + 0.5)));
  for (let y = 0; y < 5; y++) for (const x of [0, 1, 7, 8]) put('stone', x, y);
  for (let y = 0; y < 3; y++) for (let x = 2; x < 7; x++) put(y === 1 && (x === 3 || x === 5) ? 'tnt' : 'wood', x, y);
  for (const x of [2, 4, 6]) put('wood', x, 3);
  for (const x of [0, 8]) put('stone', x, 5);
  // A small wooden pyramid in front.
  const px = -6;
  for (let y = 0; y < 3; y++) for (let x = 0; x < 3 - y; x++) put('wood', px + x + y * 0.5, y);
  sleepAll(blocks);
}

function aspectOf(id) {
  const image = sprites.get(id);
  return image?.naturalWidth ? image.naturalHeight / image.naturalWidth : 0.25;
}

function rocketBody(id, x, y, angle = 0) {
  // Collision follows the fuselage, not the fin tips.
  return makeRocket(id, x, y, aspectOf(id) * 0.55, angle);
}

function reset() {
  world.clear();
  particles.length = rings.length = lights.length = 0;
  lastTouched = null;
  buildFort();
  const parked = ['s8', 'aim9x', 'tomahawk'];
  const bodies = parked.map((id, i) => {
    const b = world.add(rocketBody(id, 7 + i * 2.6, 0));
    b.y = b.hh + 0.001;
    return b;
  });
  sleepAll(bodies);
}

// ---------------------------------------------------------------------------------------------------------------
// Camera and coordinates
// ---------------------------------------------------------------------------------------------------------------

function baseScale() { return Math.min(W / (FIELD + 1), H / 13); }
function scale() { return baseScale() / camera.zoom; }
function groundScreenY() { return H * (W < 640 ? 0.84 : 0.87); }

function toScreen(x, y) {
  const s = scale();
  return [(x - camera.x) * s + W / 2, groundScreenY() - (y - camera.y0()) * s];
}
camera.y0 = () => 0;

function toWorld(sx, sy) {
  const s = scale();
  return [(sx - W / 2) / s + camera.x, (groundScreenY() - sy) / s];
}

function updateCamera(dt) {
  const s0 = baseScale();
  const viewW = W / s0, viewH = groundScreenY() / s0;
  // The field at rest, widened to keep every rocket in flight in view.
  let x0 = FIELD / 2 - viewW / 2, x1 = FIELD / 2 + viewW / 2, y1 = viewH;
  let active = false;
  for (const b of world.bodies) {
    if (b.kind !== 'rocket' || !b.armed) continue;
    const speed = Math.hypot(b.vx, b.vy);
    if (b.burn <= 0 && speed < 6) continue;
    active = true;
    const ax = b.x + b.vx * 0.5, ay = b.y + b.vy * 0.5;
    x0 = Math.min(x0, b.x - 6, ax - 6); x1 = Math.max(x1, b.x + 6, ax + 6);
    y1 = Math.max(y1, b.y + 6, ay + 6);
  }
  if (active) camera.hold = 1.4; else camera.hold -= dt;
  let zoom = Math.min(4.5, Math.max(1, (x1 - x0) / viewW, y1 / viewH));
  let cx = (x0 + x1) / 2;
  if (!active && camera.hold > 0) { zoom = camera.targetZoom; cx = camera.targetX; }
  camera.targetZoom = zoom; camera.targetX = cx;
  const k = 1 - Math.exp(-(zoom > camera.zoom ? 4 : 1.6) * dt);
  camera.zoom += (zoom - camera.zoom) * k;
  camera.x += (cx - camera.x) * k;
}

// ---------------------------------------------------------------------------------------------------------------
// Particles (world space, metres)
// ---------------------------------------------------------------------------------------------------------------

const rand = (a, b) => a + Math.random() * (b - a);

function emit(p) {
  if (particles.length > 2600) particles.splice(0, particles.length - 2600);
  particles.push(p);
}

function flame(x, y, dx, dy, strength = 1) {
  emit({ type: 'flame', x, y, vx: dx * rand(6, 12) + rand(-1.5, 1.5), vy: dy * rand(6, 12) + rand(-1.5, 1.5), life: rand(0.12, 0.28) * strength, max: 0.28, size: rand(0.22, 0.4) * strength });
}

function smoke(x, y, vx = 0, vy = 0, size = 0.4, life = 2.4, shade = 0) {
  emit({ type: 'smoke', x, y, vx: vx + rand(-0.3, 0.3), vy: vy + rand(0, 0.5), life: rand(life * 0.6, life), max: life, size, grow: rand(0.6, 1.1), shade: shade || rand(0.65, 0.9) });
}

function spark(x, y, speed = 10) {
  const a = rand(0, Math.PI * 2), v = rand(0.3, 1) * speed;
  emit({ type: 'spark', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v + 2, life: rand(0.35, 0.9), max: 0.9, size: rand(0.06, 0.11) });
}

function ember(x, y) {
  emit({ type: 'ember', x, y, vx: rand(-4, 4), vy: rand(2, 9), life: rand(1, 2.2), max: 2.2, size: rand(0.08, 0.14) });
}

function debris(x, y, colors, power = 1) {
  const a = rand(0, Math.PI * 2), v = rand(3, 14) * power;
  emit({ type: 'debris', x, y, vx: Math.cos(a) * v, vy: Math.abs(Math.sin(a)) * v + 3, life: rand(2, 3.5), max: 3.5, size: rand(0.12, 0.3), spin: rand(-12, 12), angle: rand(0, 6), color: colors[Math.floor(Math.random() * colors.length)] });
}

function dust(x, y, amount = 6) {
  for (let i = 0; i < amount; i++) emit({ type: 'dust', x: x + rand(-0.4, 0.4), y: y + rand(0, 0.2), vx: rand(-2.5, 2.5), vy: rand(0.3, 1.8), life: rand(0.5, 1.1), max: 1.1, size: rand(0.18, 0.35) });
}

function explosion(x, y, radius, body) {
  const big = radius > 5.5 ? 1.25 : 1;
  rings.push({ x, y, r: 0.3, max: radius * 1.35, life: 0.45, total: 0.45 });
  lights.push({ x, y, r: radius * 3, life: 0.55, total: 0.55 });
  flash = Math.min(1, flash + 0.55 * big);
  if (!reducedMotion) shake = Math.min(1.2, shake + 0.7 * big);
  for (let i = 0; i < 70 * big; i++) {
    const a = rand(0, Math.PI * 2), v = rand(2, 13) * big;
    emit({ type: 'flame', x: x + rand(-0.3, 0.3), y: y + rand(-0.3, 0.3), vx: Math.cos(a) * v, vy: Math.sin(a) * v + 2, life: rand(0.25, 0.65), max: 0.65, size: rand(0.35, 0.9) * big });
  }
  for (let i = 0; i < 34 * big; i++) {
    const a = rand(0, Math.PI * 2), v = rand(0.5, 4);
    smoke(x + Math.cos(a) * rand(0, 1.5), y + Math.sin(a) * rand(0, 1.2), Math.cos(a) * v, Math.sin(a) * v + 1.8, rand(0.6, 1.2), rand(2.5, 4.5), rand(0.25, 0.5));
  }
  for (let i = 0; i < 46 * big; i++) spark(x, y, 22 * big);
  for (let i = 0; i < 26 * big; i++) ember(x + rand(-1, 1), y + rand(-0.5, 0.5));
  const colors = body?.kind === 'block' ? BLOCK_COLORS[body.material] : ['#9aa1a8', '#5d636b', '#c9ccd0', '#3a3f45'];
  for (let i = 0; i < 22 * big; i++) debris(x, y, colors, big);
  if (y < 2) dust(x, 0.1, 18);
}

function stepParticles(dt) {
  const wind = 0.6;
  for (let i = particles.length - 1; i >= 0; i--) {
    const p = particles[i];
    p.life -= dt;
    if (p.life <= 0) { particles.splice(i, 1); continue; }
    switch (p.type) {
      case 'flame':
        p.vx *= Math.exp(-4 * dt); p.vy = p.vy * Math.exp(-4 * dt) + 3 * dt;
        break;
      case 'smoke':
        p.vx += (wind - p.vx) * (1 - Math.exp(-0.6 * dt)); p.vy = p.vy * Math.exp(-0.8 * dt) + 0.5 * dt;
        p.size += p.grow * dt;
        break;
      case 'dust':
        p.vx *= Math.exp(-3 * dt); p.vy = p.vy * Math.exp(-3 * dt) - 0.6 * dt; p.size += 0.5 * dt;
        break;
      case 'ember':
        p.vx *= Math.exp(-1.2 * dt); p.vy += (-3 + Math.sin(clock * 7 + i) * 2) * dt;
        break;
      default: // spark, debris
        p.vy -= 9.81 * dt;
        if (p.spin) p.angle += p.spin * dt;
    }
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    if (p.y < p.size * 0.5 && (p.type === 'spark' || p.type === 'debris')) {
      p.y = p.size * 0.5;
      p.vy = Math.abs(p.vy) * (p.type === 'spark' ? 0.35 : 0.25);
      p.vx *= 0.6;
      if (p.spin) p.spin *= 0.5;
    }
  }
  for (const list of [rings, lights]) {
    for (let i = list.length - 1; i >= 0; i--) if ((list[i].life -= dt) <= 0) list.splice(i, 1);
  }
  flash *= Math.exp(-5 * dt);
  shake *= Math.exp(-7 * dt);
}

function exhaust(b, dt) {
  const c = Math.cos(b.a), s = Math.sin(b.a);
  const [nx, ny] = b.world(-b.hw - 0.1, 0);
  const n = Math.max(1, Math.round(dt * 220));
  for (let i = 0; i < n; i++) flame(nx, ny, -c, -s, 1);
  if (Math.random() < dt * 60) smoke(nx - c * 0.6, ny - s * 0.6, -c * 2 + b.vx * 0.15, -s * 2 + b.vy * 0.15, 0.35, rand(1.8, 3));
}

// ---------------------------------------------------------------------------------------------------------------
// Firing
// ---------------------------------------------------------------------------------------------------------------

function launcherAim() {
  const [tx, ty] = pointer.inside && !pointer.overBody ? toWorld(pointer.sx, pointer.sy) : [FORT_X + 4, 5];
  const a = Math.atan2(ty - LAUNCHER.y, tx - LAUNCHER.x);
  return Math.max(0.5, Math.min(Math.PI * 0.47, a));
}

function fire(tx, ty) {
  if (cooldown > 0) return;
  cooldown = 0.35;
  const angle = Math.atan2(ty - LAUNCHER.y, tx - LAUNCHER.x);
  const a = Math.max(0.5, Math.min(Math.PI * 0.47, angle));
  const spec = ROCKETS[ammo];
  const x = LAUNCHER.x + Math.cos(a) * (spec.length / 2 + 0.4), y = LAUNCHER.y + Math.sin(a) * (spec.length / 2 + 0.4);
  const b = world.add(rocketBody(ammo, x, y, a));
  b.vx = Math.cos(a) * 8; b.vy = Math.sin(a) * 8;
  world.ignite(b, { x: tx, y: Math.max(0.3, ty) });
  lastTouched = b;
  // Backblast.
  const [bx, by] = b.world(-b.hw, 0);
  for (let i = 0; i < 26; i++) flame(bx, by, -Math.cos(a), -Math.sin(a), 1.3);
  for (let i = 0; i < 14; i++) smoke(bx + rand(-0.6, 0.6), by + rand(-0.2, 0.4), -Math.cos(a) * rand(1, 5), rand(0.2, 1.5), rand(0.4, 0.8), rand(1.5, 3));
  dust(LAUNCHER.x - 1, 0.1, 10);
  shake = Math.min(1, shake + (reducedMotion ? 0 : 0.18));
  const rockets = world.bodies.filter(r => r.kind === 'rocket');
  if (rockets.length > 14) world.remove(rockets.find(r => r !== b && r !== world.joint?.body) ?? rockets[0]);
}

function ignite(b) {
  if (!b || b.kind !== 'rocket') return;
  if (world.ignite(b)) {
    const [bx, by] = b.world(-b.hw, 0);
    for (let i = 0; i < 20; i++) flame(bx, by, -Math.cos(b.a), -Math.sin(b.a), 1.2);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------------------------------------------

function localPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return [event.clientX - rect.left, event.clientY - rect.top];
}

canvas.addEventListener('pointerdown', event => {
  if (event.button !== 0) return;
  const [sx, sy] = localPoint(event);
  const [x, y] = toWorld(sx, sy);
  Object.assign(pointer, { sx, sy, x, y, inside: true });
  const body = world.bodyAt(x, y, 0.25 * camera.zoom);
  event.preventDefault();
  stage.focus({ preventScroll: true });
  if (body) {
    const now = performance.now();
    if (lastTap.body === body && now - lastTap.time < 350) ignite(body);
    lastTap = { body, time: now };
    world.grab(body, x, y);
    lastTouched = body;
    canvas.setPointerCapture(event.pointerId);
    stage.classList.add('is-grabbing');
  } else if (y > -0.5) {
    fire(x, y);
  }
});

canvas.addEventListener('pointermove', event => {
  const [sx, sy] = localPoint(event);
  const [x, y] = toWorld(sx, sy);
  Object.assign(pointer, { sx, sy, x, y, inside: true });
  if (world.joint) { world.joint.tx = x; world.joint.ty = Math.max(y, 0.2); }
  else pointer.overBody = world.bodyAt(x, y, 0.25 * camera.zoom);
  stage.classList.toggle('over-body', !!pointer.overBody);
});

function release() {
  world.release();
  stage.classList.remove('is-grabbing');
}
canvas.addEventListener('pointerup', release);
canvas.addEventListener('pointercancel', release);
canvas.addEventListener('pointerleave', () => { pointer.inside = false; pointer.overBody = null; });

stage.addEventListener('keydown', event => {
  if (event.code === 'Space') { event.preventDefault(); ignite(world.joint?.body ?? lastTouched); }
  if (event.key === 'f' || event.key === 'F') fire(FORT_X + 4, 3);
  if (event.key === 'r' || event.key === 'R') reset();
});

for (const button of ammoButtons) {
  button.addEventListener('click', () => {
    ammo = button.dataset.rocket;
    for (const b of ammoButtons) b.setAttribute('aria-pressed', String(b === button));
  });
}
document.querySelector('#fire-fort')?.addEventListener('click', () => {
  cooldown = 0;
  const blocks = world.bodies.filter(b => b.kind === 'block' && b.x > LAUNCHER.x + 8);
  const target = blocks[Math.floor(Math.random() * blocks.length)];
  if (target) fire(target.x, target.y); else fire(FORT_X + 4, 2);
});
document.querySelector('#rebuild-fort')?.addEventListener('click', buildFort);
document.querySelector('#reset-world')?.addEventListener('click', reset);
slowButton?.addEventListener('click', () => {
  slow = !slow;
  slowButton.setAttribute('aria-pressed', String(slow));
});

// ---------------------------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------------------------

let sky = null, stars = [], clouds = [], hills = [];

function buildBackdrop() {
  const r = seeded(21);
  stars = Array.from({ length: 90 }, () => ({ x: r(), y: r() * 0.55, s: r() < 0.15 ? 2 : 1, t: r() * 6 }));
  clouds = Array.from({ length: 7 }, (_, i) => ({ x: r() * 1.4, y: 0.12 + r() * 0.32, w: 4 + Math.floor(r() * 6), speed: 0.004 + r() * 0.006, depth: 0.5 + r() * 0.5, seed: i * 31 + 7 }));
  hills = [0, 1, 2].map(layer => {
    const steps = [];
    let h = 0.5;
    for (let i = 0; i < 64; i++) { h = Math.max(0.15, Math.min(1, h + (r() - 0.5) * 0.45)); steps.push(h); }
    return steps;
  });
}

function drawSky() {
  if (!sky || sky.h !== H) {
    sky = ctx.createLinearGradient(0, 0, 0, groundScreenY());
    sky.addColorStop(0, '#0b1026');
    sky.addColorStop(0.42, '#2a2a5e');
    sky.addColorStop(0.72, '#7a3f6b');
    sky.addColorStop(0.9, '#e9785a');
    sky.addColorStop(1, '#ffbe7a');
    sky.h = H;
  }
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, W, groundScreenY() + 2);
  const px = Math.max(2, Math.round(W / 480));
  for (const star of stars) {
    const tw = 0.55 + 0.45 * Math.sin(clock * 1.7 + star.t * 5);
    ctx.fillStyle = `rgba(255,248,230,${0.75 * tw})`;
    ctx.fillRect(Math.round(star.x * W), Math.round(star.y * groundScreenY()), star.s * px, star.s * px);
  }
  // Low sun, blocky with a soft halo.
  const sunX = W * 0.74 - (camera.x - FIELD / 2) * scale() * 0.05, sunY = groundScreenY() * 0.78;
  const halo = ctx.createRadialGradient(sunX, sunY, 0, sunX, sunY, W * 0.35);
  halo.addColorStop(0, 'rgba(255,214,140,0.55)');
  halo.addColorStop(1, 'rgba(255,214,140,0)');
  ctx.fillStyle = halo;
  ctx.fillRect(0, 0, W, groundScreenY());
  const sun = Math.round(Math.min(W, H) * 0.09 / px) * px;
  ctx.fillStyle = '#ffe2a0';
  ctx.fillRect(Math.round(sunX - sun / 2), Math.round(sunY - sun / 2), sun, sun);
  ctx.fillStyle = '#fff3cf';
  ctx.fillRect(Math.round(sunX - sun / 2 + px * 2), Math.round(sunY - sun / 2 + px * 2), sun - px * 4, sun - px * 4);
}

function drawClouds(dt) {
  const block = Math.max(6, Math.round(W / 110));
  for (const c of clouds) {
    c.x += c.speed * dt;
    if (c.x > 1.3) c.x = -0.3;
    const x0 = Math.round((c.x * W - (camera.x - FIELD / 2) * scale() * 0.1 * c.depth) / block) * block;
    const y0 = Math.round(c.y * groundScreenY() / block) * block;
    const r = seeded(c.seed);
    ctx.fillStyle = `rgba(255,214,200,${0.18 + 0.2 * c.depth})`;
    for (let i = 0; i < c.w; i++) {
      const h = 1 + Math.floor(r() * 2);
      ctx.fillRect(x0 + i * block * 1.5, y0 - (h - 1) * block, block * 1.5, h * block);
    }
  }
}

function drawHills() {
  const colors = ['#3a2d5c', '#2c2448', '#211b37'];
  const parallax = [0.08, 0.18, 0.32];
  const heights = [0.32, 0.24, 0.16];
  const gy = groundScreenY();
  hills.forEach((steps, layer) => {
    const block = Math.max(10, Math.round(W / 40)) * (1 + layer * 0.3);
    const shift = (camera.x - FIELD / 2) * scale() * parallax[layer];
    ctx.fillStyle = colors[layer];
    const start = Math.floor(shift / block) - 1;
    for (let i = start; i < start + W / block + 3; i++) {
      const h = steps[((i % 64) + 64) % 64] * heights[layer] * gy;
      const x = i * block - shift;
      ctx.fillRect(Math.round(x), Math.round(gy - h), Math.ceil(block) + 1, Math.ceil(h) + 1);
    }
  });
}

function drawGround() {
  const s = scale();
  const [, gy] = toScreen(0, 0);
  const first = Math.floor(toWorld(0, 0)[0]) - 1, last = Math.ceil(toWorld(W, 0)[0]) + 1;
  ctx.imageSmoothingEnabled = false;
  for (let x = first; x <= last; x++) {
    const [sx] = toScreen(x, 0);
    ctx.drawImage(TEX.grass, Math.floor(sx), Math.floor(gy), Math.ceil(s) + 1, Math.ceil(s) + 1);
    for (let y = 1; gy + y * s < H; y++) ctx.drawImage(TEX.dirt, Math.floor(sx), Math.floor(gy + y * s), Math.ceil(s) + 1, Math.ceil(s) + 1);
  }
  const fade = ctx.createLinearGradient(0, gy, 0, H);
  fade.addColorStop(0, 'rgba(12,10,24,0.15)');
  fade.addColorStop(1, 'rgba(12,10,24,0.75)');
  ctx.fillStyle = fade;
  ctx.fillRect(0, gy, W, H - gy);
}

function drawShadows() {
  const s = scale();
  ctx.fillStyle = 'rgba(10,6,20,0.28)';
  for (const b of world.bodies) {
    const height = b.y - b.radius;
    if (height > 8) continue;
    const [sx, sy] = toScreen(b.x, 0);
    const w = (b.kind === 'rocket' ? b.hw * 1.6 : b.hw * 2.2) * s * (1 - height / 12);
    ctx.globalAlpha = Math.max(0, 1 - height / 8);
    ctx.beginPath();
    ctx.ellipse(sx, sy + 1, w / 2, Math.max(2, s * 0.12), 0, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalAlpha = 1;
}

function drawLauncher() {
  const s = scale();
  const [bx, by] = toScreen(LAUNCHER.x, 0);
  ctx.imageSmoothingEnabled = false;
  // Base.
  ctx.drawImage(TEX.launcher, Math.round(bx - s * 0.9), Math.round(by - s * 0.7), Math.round(s * 1.8), Math.round(s * 0.7));
  // Rail and the loaded rocket, both turning toward the pointer.
  const a = launcherAim();
  const spec = ROCKETS[ammo];
  const [px, py] = toScreen(LAUNCHER.x, LAUNCHER.y);
  ctx.save();
  ctx.translate(px, py);
  ctx.rotate(-a);
  ctx.fillStyle = '#2b333d';
  ctx.fillRect(-s * 0.4, -s * 0.06, s * (spec.length + 0.9), s * 0.16);
  ctx.fillStyle = '#596779';
  ctx.fillRect(-s * 0.4, -s * 0.06, s * (spec.length + 0.9), s * 0.04);
  const image = sprites.get(ammo);
  if (image?.complete && cooldown <= 0.05) {
    const len = spec.length * s, h = len * aspectOf(ammo);
    ctx.globalAlpha = 0.95;
    ctx.drawImage(image, s * 0.4, -h - s * 0.04, len, h);
    ctx.globalAlpha = 1;
  }
  ctx.restore();
  ctx.fillStyle = '#e2b13c';
  ctx.fillRect(Math.round(px - s * 0.12), Math.round(py - s * 0.12), Math.round(s * 0.24), Math.round(s * 0.24));
}

function drawBodies() {
  const s = scale();
  ctx.imageSmoothingEnabled = false;
  for (const b of world.bodies) {
    const [sx, sy] = toScreen(b.x, b.y);
    ctx.save();
    ctx.translate(sx, sy);
    ctx.rotate(-b.a);
    if (b.kind === 'block') {
      const size = b.hw * 2 * s;
      ctx.drawImage(TEX[b.material], -size / 2, -size / 2, size + 0.5, size + 0.5);
      if (b.material === 'tnt' && b.fuse > 0 && Math.floor(clock * 16) % 2) {
        ctx.fillStyle = 'rgba(255,255,255,0.65)';
        ctx.fillRect(-size / 2, -size / 2, size, size);
      }
      ctx.strokeStyle = 'rgba(20,12,8,0.35)';
      ctx.lineWidth = Math.max(1, s * 0.03);
      ctx.strokeRect(-size / 2, -size / 2, size, size);
    } else if (b.kind === 'rocket') {
      const image = sprites.get(b.rocket);
      const len = b.hw * 2 * s, h = len * aspectOf(b.rocket);
      if (image?.complete) ctx.drawImage(image, -len / 2, -h / 2, len, h);
    }
    if (b === pointer.overBody || b === world.joint?.body) {
      ctx.strokeStyle = 'rgba(255,240,190,0.9)';
      ctx.lineWidth = 2;
      ctx.setLineDash([4, 4]);
      ctx.strokeRect(-b.hw * s - 3, -b.hh * s - 3, b.hw * 2 * s + 6, b.hh * 2 * s + 6);
      ctx.setLineDash([]);
    }
    ctx.restore();
  }
}

function drawParticles(additive) {
  const s = scale();
  ctx.globalCompositeOperation = additive ? 'lighter' : 'source-over';
  for (const p of particles) {
    const glow = p.type === 'flame' || p.type === 'spark' || p.type === 'ember';
    if (glow !== additive) continue;
    const t = 1 - p.life / p.max;
    const [sx, sy] = toScreen(p.x, p.y);
    let size = Math.max(1.5, p.size * s);
    switch (p.type) {
      case 'flame': {
        // White core, yellow, orange, then a dull red as it cools.
        const r = 255, g = Math.round(250 - 190 * t), bl = Math.round(220 * Math.max(0, 1 - t * 3));
        ctx.fillStyle = `rgba(${r},${g},${bl},${(1 - t) * 0.85})`;
        size *= 1 - t * 0.5;
        break;
      }
      case 'spark': ctx.fillStyle = `rgba(255,${Math.round(230 - 80 * t)},120,${1 - t})`; break;
      case 'ember': ctx.fillStyle = `rgba(255,${Math.round(150 - 60 * t)},40,${(1 - t) * (0.6 + 0.4 * Math.sin(clock * 20 + p.x))})`; break;
      case 'smoke': {
        const v = Math.round(255 * p.shade * (0.55 + 0.45 * (1 - t)));
        ctx.fillStyle = `rgba(${v},${Math.round(v * 0.94)},${Math.round(v * 0.98)},${0.42 * Math.sin(Math.PI * Math.min(1, t * 1.6 + 0.08))})`;
        break;
      }
      case 'dust': ctx.fillStyle = `rgba(150,118,86,${0.5 * (1 - t)})`; break;
      case 'debris': ctx.fillStyle = p.color; ctx.globalAlpha = Math.min(1, p.life * 2); break;
    }
    if (p.type === 'debris') {
      ctx.save(); ctx.translate(sx, sy); ctx.rotate(p.angle);
      ctx.fillRect(-size / 2, -size / 2, size, size);
      ctx.restore(); ctx.globalAlpha = 1;
    } else {
      const q = Math.max(1, Math.round(size));
      ctx.fillRect(Math.round(sx - q / 2), Math.round(sy - q / 2), q, q);
    }
  }
  ctx.globalCompositeOperation = 'source-over';
}

function drawLights() {
  const s = scale();
  ctx.globalCompositeOperation = 'lighter';
  for (const l of lights) {
    const t = l.life / l.total;
    const [sx, sy] = toScreen(l.x, l.y);
    const g = ctx.createRadialGradient(sx, sy, 0, sx, sy, l.r * s);
    g.addColorStop(0, `rgba(255,200,120,${0.75 * t})`);
    g.addColorStop(0.35, `rgba(255,120,50,${0.3 * t})`);
    g.addColorStop(1, 'rgba(255,80,20,0)');
    ctx.fillStyle = g;
    ctx.fillRect(sx - l.r * s, sy - l.r * s, l.r * s * 2, l.r * s * 2);
  }
  // Burning motors light their surroundings.
  for (const b of world.bodies) {
    if (!(b.burn > 0)) continue;
    const [nx, ny] = b.world(-b.hw - 0.2, 0);
    const [sx, sy] = toScreen(nx, ny);
    const r = s * (2.2 + Math.random() * 0.4);
    const g = ctx.createRadialGradient(sx, sy, 0, sx, sy, r);
    g.addColorStop(0, 'rgba(255,220,150,0.7)');
    g.addColorStop(1, 'rgba(255,120,40,0)');
    ctx.fillStyle = g;
    ctx.fillRect(sx - r, sy - r, r * 2, r * 2);
  }
  for (const ring of rings) {
    const t = 1 - ring.life / ring.total;
    const [sx, sy] = toScreen(ring.x, ring.y);
    ctx.strokeStyle = `rgba(255,236,200,${0.6 * (1 - t)})`;
    ctx.lineWidth = Math.max(1, s * 0.25 * (1 - t));
    ctx.beginPath();
    ctx.arc(sx, sy, ring.max * s * (1 - (1 - t) ** 3), 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.globalCompositeOperation = 'source-over';
}

function drawPointer() {
  if (world.joint) {
    const [ax, ay] = world.joint.anchor();
    const [sx, sy] = toScreen(ax, ay);
    const [tx, ty] = toScreen(world.joint.tx, world.joint.ty);
    ctx.strokeStyle = 'rgba(255,240,190,0.85)';
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 5]);
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(tx, ty); ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = '#ffe9a8';
    ctx.fillRect(Math.round(sx - 3), Math.round(sy - 3), 6, 6);
    return;
  }
  if (!pointer.inside || pointer.overBody || matchMedia('(hover: none)').matches) return;
  // Crosshair where a click fires.
  const { sx, sy } = pointer;
  const r = 12, k = 4;
  ctx.fillStyle = cooldown > 0 ? 'rgba(255,240,190,0.4)' : 'rgba(255,240,190,0.95)';
  ctx.fillRect(sx - r - k, sy - 1, k + 4, 2); ctx.fillRect(sx + r - 4, sy - 1, k + 4, 2);
  ctx.fillRect(sx - 1, sy - r - k, 2, k + 4); ctx.fillRect(sx - 1, sy + r - 4, 2, k + 4);
  ctx.fillRect(sx - 1, sy - 1, 2, 2);
}

function draw(dt) {
  const ox = shake > 0.01 ? rand(-1, 1) * shake * 9 : 0, oy = shake > 0.01 ? rand(-1, 1) * shake * 9 : 0;
  ctx.setTransform(ratio, 0, 0, ratio, ox * ratio, oy * ratio);
  drawSky();
  drawClouds(dt);
  drawHills();
  drawGround();
  drawShadows();
  drawParticles(false);
  drawLauncher();
  drawBodies();
  drawParticles(true);
  drawLights();
  drawPointer();
  if (flash > 0.01) {
    ctx.fillStyle = `rgba(255,236,200,${flash * 0.35})`;
    ctx.fillRect(-20, -20, W + 40, H + 40);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Loop
// ---------------------------------------------------------------------------------------------------------------

function resize() {
  const rect = stage.getBoundingClientRect();
  W = Math.max(1, rect.width); H = Math.max(1, rect.height);
  ratio = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(W * ratio);
  canvas.height = Math.round(H * ratio);
  sky = null;
}

let last = performance.now(), accumulator = 0, visible = true;
new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; }).observe(stage);

function frame(now) {
  requestAnimationFrame(frame);
  const real = Math.min(0.05, (now - last) / 1000);
  last = now;
  if (!visible || document.hidden) return;
  advance(real);
}

function advance(real) {
  const dt = real * (slow ? 0.25 : 1);
  clock += dt;
  cooldown = Math.max(0, cooldown - real);
  accumulator += dt;
  let steps = 0;
  while (accumulator >= STEP && steps < 6) {
    for (const b of world.bodies) if (b.burn > 0) exhaust(b, STEP);
    world.step(STEP);
    for (const e of world.drain()) {
      if (e.type === 'explode') explosion(e.x, e.y, e.radius, e.body);
      else if (e.speed > 5) {
        dust(e.x, Math.max(0.05, e.y), Math.min(10, Math.round(e.speed / 2)));
        const hard = [e.a, e.b].some(b => b.kind === 'rocket' || b.material === 'stone');
        if (hard && e.speed > 7) for (let i = 0; i < 6; i++) spark(e.x, e.y, e.speed * 0.6);
      }
    }
    accumulator -= STEP;
    steps++;
  }
  if (steps === 6) accumulator = 0;
  // Spent motors keep trailing thin smoke while they are fast.
  for (const b of world.bodies) {
    if (b.kind === 'rocket' && b.armed && b.burn <= 0 && Math.hypot(b.vx, b.vy) > 12 && Math.random() < dt * 25) {
      const [tx, ty] = b.world(-b.hw, 0);
      smoke(tx, ty, 0, 0, 0.25, 1.6);
    }
  }
  stepParticles(dt);
  updateCamera(real);
  draw(dt);
}

async function start() {
  await Promise.all(Object.keys(ROCKETS).map(id => new Promise(resolve => {
    const image = new Image();
    image.onload = image.onerror = resolve;
    image.src = `./sprites/${id}.png`;
    sprites.set(id, image);
  })));
  buildBackdrop();
  new ResizeObserver(resize).observe(stage);
  resize();
  reset();
  stage.classList.add('is-ready');
  requestAnimationFrame(t => { last = t; frame(t); });
}

start();

// Exposed for the test page and curious visitors.
window.physicalistSandbox = { world, fire, reset, ignite, draw, particles, advance, BURN_TIME };
