// A deliberately small 2D flight toy for the website, not the game's physics API.
export const ROCKETS = Object.freeze({
  c75: { label: 'C-75', width: 177, wing: 1.05, drag: 0.35 },
  c25: { label: 'C-25', width: 153, wing: 0.92, drag: 0.38 },
  aim9x: { label: 'AIM-9X', width: 141, wing: 0.78, drag: 0.29 },
  rim7: { label: 'RIM-7', width: 139, wing: 0.96, drag: 0.32 },
  nine_k_119m: { label: '9K119M', width: 116, wing: 0.37, drag: 0.28 },
  kh101: { label: 'Kh-101', width: 181, wing: 1.42, drag: 0.31 },
  x25ml: { label: 'Kh-25ML', width: 145, wing: 0.81, drag: 0.35 },
  vihr: { label: 'Vikhr', width: 134, wing: 0.65, drag: 0.3 },
  tomahawk: { label: 'Tomahawk', width: 173, wing: 1.25, drag: 0.31 },
  s8: { label: 'S-8', width: 116, wing: 0.25, drag: 0.32 },
});

export function wrapAngle(angle) {
  return Math.atan2(Math.sin(angle), Math.cos(angle));
}

export function makeRocket(id, x, y, image) {
  const spec = ROCKETS[id];
  if (!spec) throw new Error(`Unknown rocket: ${id}`);
  const ratio = image?.naturalWidth && image?.naturalHeight
    ? image.naturalHeight / image.naturalWidth : 0.25;
  return {
    id, image, x, y, vx: 0, vy: 0, angle: 0, spin: 0,
    width: spec.width, height: Math.max(16, spec.width * ratio),
    held: false, parked: false, age: 0,
  };
}

export function stepRocket(rocket, dt, bounds) {
  if (rocket.held || rocket.parked || dt <= 0) return rocket;
  const spec = ROCKETS[rocket.id];
  dt = Math.min(dt, 0.035);
  const speed = Math.hypot(rocket.vx, rocket.vy);
  const forward = Math.cos(rocket.angle) * rocket.vx + Math.sin(rocket.angle) * rocket.vy;
  const cross = -Math.sin(rocket.angle) * rocket.vx + Math.cos(rocket.angle) * rocket.vy;

  // Broad wings resist sideways motion and create lift only while moving.
  const sideDamping = Math.min(0.7, dt * (1.1 + spec.wing * Math.min(speed / 95, 4)));
  rocket.vx += Math.sin(rocket.angle) * cross * sideDamping;
  rocket.vy -= Math.cos(rocket.angle) * cross * sideDamping;
  if (forward > 0) {
    rocket.vy -= Math.min(170, spec.wing * forward * forward * 0.00065) * dt;
  }
  const resistance = Math.exp(-(spec.drag + speed * 0.00022) * dt);
  rocket.vx *= resistance;
  rocket.vy = rocket.vy * resistance + 205 * dt;

  if (speed > 35) {
    const course = Math.atan2(rocket.vy, rocket.vx);
    rocket.spin += wrapAngle(course - rocket.angle) * Math.min(speed / 125, 4) * 6.5 * dt;
  }
  rocket.spin *= Math.exp(-5.5 * dt);
  rocket.angle = wrapAngle(rocket.angle + rocket.spin * dt);
  rocket.x += rocket.vx * dt;
  rocket.y += rocket.vy * dt;
  rocket.age += dt;

  const ground = bounds.height - 80;
  const radius = Math.max(rocket.height * 0.46, 9);
  if (rocket.y + radius > ground) {
    rocket.y = ground - radius;
    if (rocket.vy > 20) rocket.vy *= -0.29;
    else rocket.vy = 0;
    rocket.vx *= Math.exp(-2.4 * dt);
    rocket.spin *= 0.6;
  }

  const margin = rocket.width * 0.55;
  if (rocket.x > bounds.width + margin || rocket.x < -margin || rocket.y < -margin) {
    rocket.x = -margin + 2;
    rocket.y = Math.min(ground - radius - 18, Math.max(55, bounds.height * 0.27));
    rocket.vx = Math.max(180, Math.abs(rocket.vx) * 0.72);
    rocket.vy = -20;
    rocket.angle = 0;
    rocket.spin = 0;
  }
  return rocket;
}
