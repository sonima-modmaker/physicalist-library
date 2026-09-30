import { ROCKETS, makeRocket, stepRocket } from './physics.mjs';

const stage = document.querySelector('#world-stage');
const canvas = document.querySelector('#rocket-canvas');
const context = canvas.getContext('2d', { alpha: true });
const buttons = [...document.querySelectorAll('.rocket-option')];
const images = new Map();
let rockets = [];
let trails = [];
let grabbed = null;
let samples = [];
let bounds = { width: 1, height: 1 };
let lastTime = performance.now();

for (const id of Object.keys(ROCKETS)) {
  const image = new Image();
  image.src = `./sprites/${id}.png`;
  image.addEventListener('load', () => {
    for (const rocket of rockets) {
      if (rocket.id === id && rocket.parked) {
        rocket.height = Math.max(16, rocket.width * image.naturalHeight / image.naturalWidth);
      }
    }
  });
  images.set(id, image);
}

function resize() {
  const rect = stage.getBoundingClientRect();
  const old = bounds;
  bounds = { width: rect.width, height: rect.height };
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(rect.width * ratio);
  canvas.height = Math.round(rect.height * ratio);
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.imageSmoothingEnabled = true;
  if (old.width > 1) {
    for (const rocket of rockets) {
      rocket.x *= bounds.width / old.width;
      rocket.y *= bounds.height / old.height;
    }
  }
  layoutParked();
}

function layoutParked() {
  const parked = rockets.filter(rocket => rocket.parked);
  const columns = bounds.width < 610 ? 2 : 5;
  const rows = Math.ceil(parked.length / columns);
  parked.forEach((rocket, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const width = Math.min(ROCKETS[rocket.id].width, bounds.width / columns - 28);
    rocket.height *= width / rocket.width;
    rocket.width = width;
    rocket.x = bounds.width * (column + 0.5) / columns;
    const top = bounds.width < 610 ? 180 : 160;
    rocket.y = top + (bounds.height - top - 98) * (row + 0.5) / rows;
  });
}

function addRocket(id, x = bounds.width * 0.34, y = bounds.height * 0.37) {
  const rocket = makeRocket(id, x, y, images.get(id));
  rocket.vx = 90;
  rockets.push(rocket);
  if (rockets.length > 16) rockets.shift();
  return rocket;
}

function reset() {
  rockets = [];
  trails = [];
  grabbed = null;
  stage.classList.remove('is-grabbing');
  for (const id of Object.keys(ROCKETS)) {
    addRocket(id).parked = true;
  }
  layoutParked();
}

function point(event) {
  const rect = canvas.getBoundingClientRect();
  return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

function rocketAt(x, y) {
  for (let index = rockets.length - 1; index >= 0; index--) {
    const rocket = rockets[index];
    const dx = x - rocket.x;
    const dy = y - rocket.y;
    const c = Math.cos(rocket.angle);
    const s = Math.sin(rocket.angle);
    const localX = c * dx + s * dy;
    const localY = -s * dx + c * dy;
    if (Math.abs(localX) <= rocket.width * 0.52 && Math.abs(localY) <= Math.max(rocket.height * 0.65, 19)) {
      return rocket;
    }
  }
  return null;
}

canvas.addEventListener('pointerdown', event => {
  if (event.button !== 0) return;
  const { x, y } = point(event);
  grabbed = rocketAt(x, y);
  if (!grabbed) return;
  event.preventDefault();
  canvas.setPointerCapture(event.pointerId);
  rockets.splice(rockets.indexOf(grabbed), 1);
  rockets.push(grabbed);
  grabbed.held = true;
  grabbed.parked = false;
  grabbed.vx = grabbed.vy = grabbed.spin = 0;
  grabbed.dragOffsetX = grabbed.x - x;
  grabbed.dragOffsetY = grabbed.y - y;
  samples = [{ x: grabbed.x, y: grabbed.y, time: performance.now() }];
  stage.classList.add('is-grabbing');
});

canvas.addEventListener('pointermove', event => {
  if (!grabbed) return;
  const p = point(event);
  grabbed.x = p.x + grabbed.dragOffsetX;
  grabbed.y = p.y + grabbed.dragOffsetY;
  const now = performance.now();
  samples.push({ x: grabbed.x, y: grabbed.y, time: now });
  while (samples.length > 2 && samples[0].time < now - 120) samples.shift();
});

function release() {
  if (!grabbed) return;
  const first = samples[0];
  const last = samples.at(-1);
  const seconds = Math.max(0.016, (last.time - first.time) / 1000);
  grabbed.vx = Math.max(-1000, Math.min(1000, (last.x - first.x) / seconds));
  grabbed.vy = Math.max(-1000, Math.min(1000, (last.y - first.y) / seconds));
  grabbed.held = false;
  grabbed = null;
  samples = [];
  stage.classList.remove('is-grabbing');
}
canvas.addEventListener('pointerup', release);
canvas.addEventListener('pointercancel', release);
canvas.addEventListener('lostpointercapture', release);

for (const button of buttons) {
  button.addEventListener('click', () => {
    const rocket = addRocket(button.dataset.rocket, bounds.width * 0.23, bounds.height * 0.31);
    rocket.vx = 120;
    stage.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
}
document.querySelector('#reset-world').addEventListener('click', reset);
new ResizeObserver(resize).observe(stage);
resize();
reset();

function draw(now) {
  const dt = Math.min((now - lastTime) / 1000, 0.035);
  lastTime = now;
  context.clearRect(0, 0, bounds.width, bounds.height);
  trails = trails.filter(particle => particle.life > 0);
  for (const particle of trails) {
    particle.life -= dt;
    particle.x += particle.vx * dt;
    particle.y += particle.vy * dt;
    context.fillStyle = `rgba(255,244,192,${Math.max(0, particle.life / 0.5) * 0.48})`;
    context.fillRect(particle.x, particle.y, particle.size, particle.size);
  }
  rockets.forEach((rocket, index) => {
    stepRocket(rocket, dt, bounds);
    if (!rocket.image.complete || !rocket.image.naturalWidth) return;
    if (!rocket.parked && !rocket.held && Math.hypot(rocket.vx, rocket.vy) > 140) {
      rocket.trailClock = (rocket.trailClock || 0) + dt;
      if (rocket.trailClock > 0.035) {
        rocket.trailClock = 0;
        trails.push({
          x: rocket.x - Math.cos(rocket.angle) * rocket.width * 0.5,
          y: rocket.y - Math.sin(rocket.angle) * rocket.width * 0.5,
          vx: -rocket.vx * 0.08, vy: -rocket.vy * 0.08,
          size: 2 + Math.min(3, Math.abs(rocket.vx) / 200), life: 0.5,
        });
        if (trails.length > 180) trails.shift();
      }
    }
    context.save();
    const bob = rocket.parked ? Math.sin(now * 0.0017 + index * 1.8) * 2.5 : 0;
    context.translate(rocket.x, rocket.y + bob);
    context.rotate(rocket.angle);
    context.shadowColor = '#1a281d77';
    context.shadowBlur = 0;
    context.shadowOffsetX = 5;
    context.shadowOffsetY = 7;
    context.drawImage(rocket.image, -rocket.width / 2, -rocket.height / 2, rocket.width, rocket.height);
    context.restore();
  });
  requestAnimationFrame(draw);
}
requestAnimationFrame(draw);
