import assert from 'node:assert/strict';
import test from 'node:test';
import { World, makeBlock, makeRocket, makePlane } from './physics.mjs';

const run = (world, seconds) => { for (let i = 0; i < seconds * 120; i++) world.step(1 / 120); };

test('a dropped block comes to rest on the ground', () => {
  const world = new World();
  const block = world.add(makeBlock('wood', 5, 4));
  run(world, 3);
  assert.ok(Math.abs(block.y - 0.5) < 0.03, `y=${block.y}`);
  assert.ok(Math.hypot(block.vx, block.vy) < 0.05);
});

test('a ten-block tower stands still', () => {
  const world = new World();
  const blocks = [];
  for (let i = 0; i < 10; i++) blocks.push(world.add(makeBlock(i % 2 ? 'stone' : 'wood', 20, 0.5 + i * 1.001)));
  run(world, 5);
  const top = blocks.at(-1);
  assert.ok(Math.abs(top.x - 20) < 0.05, `top drifted to x=${top.x}`);
  assert.ok(top.y > 9.3, `top sank to y=${top.y}`);
});

test('a thrown rocket turns its nose into the wind', () => {
  const world = new World();
  const rocket = world.add(makeRocket('aim9x', 0, 20));
  rocket.vx = 25; rocket.vy = 8; rocket.a = -0.8;
  run(world, 1);
  const course = Math.atan2(rocket.vy, rocket.vx);
  assert.ok(Math.abs(course - rocket.a) < 0.2, `course=${course} nose=${rocket.a}`);
});

test('wide wings glide farther than a small unguided rocket', () => {
  const world = new World(-500, 500);
  const winged = world.add(makeRocket('kh101', 0, 40));
  const small = world.add(makeRocket('s8', 0, 80));
  winged.vx = small.vx = 30;
  run(world, 1.5);
  assert.ok(40 - winged.y < 80 - small.y, `winged fell ${40 - winged.y}, small fell ${80 - small.y}`);
});

test('a launched rocket climbs under thrust and flies toward its target', () => {
  const world = new World(-500, 500);
  const rocket = world.add(makeRocket('c75', 0, 1, 0.2, 0.9));
  world.ignite(rocket, { x: 60, y: 30 });
  run(world, 1.5);
  assert.ok(rocket.y > 8, `y=${rocket.y}`);
  assert.ok(rocket.x > 8, `x=${rocket.x}`);
  assert.ok(Math.hypot(rocket.vx, rocket.vy) > 18);
});

test('an armed rocket explodes on impact and throws blocks', () => {
  const world = new World();
  const block = world.add(makeBlock('wood', 12, 0.5));
  const rocket = world.add(makeRocket('vihr', 6, 0.6));
  world.ignite(rocket);
  let exploded = false;
  for (let i = 0; i < 240 && !exploded; i++) {
    world.step(1 / 120);
    exploded = world.drain().some(e => e.type === 'explode');
  }
  assert.ok(exploded);
  assert.ok(!world.bodies.includes(rocket));
  run(world, 0.1);
  assert.ok(Math.hypot(block.vx, block.vy) > 2 || block.y > 1, 'block did not move');
});

test('a grabbed block follows the pointer', () => {
  const world = new World();
  const block = world.add(makeBlock('stone', 5, 0.5));
  run(world, 0.5);
  const joint = world.grab(block, 5, 0.5);
  joint.tx = 8; joint.ty = 4;
  run(world, 1.5);
  const [ax, ay] = joint.anchor();
  assert.ok(Math.hypot(ax - 8, ay - 4) < 0.2, `anchor at ${ax}, ${ay}`);
});

test('sleeping stacks wake cleanly after a blast', () => {
  const world = new World();
  const blocks = [];
  for (let y = 0; y < 4; y++) for (let x = 0; x < 3; x++) blocks.push(world.add(makeBlock('wood', 20 + x, 0.5 + y * 1.0005)));
  for (const b of blocks) { b.asleep = true; b.quiet = 1; }
  run(world, 1);
  world.explode(19, 1, 5, 1);
  run(world, 4);
  for (const b of blocks) {
    assert.ok(Number.isFinite(b.x + b.y + b.a), 'non-finite block');
    assert.ok(b.y < 11 && b.y > -world.terrain.depth, `block at y=${b.y}`);
  }
});

test('a blast on the ground digs a crater and what stood there falls in', () => {
  const world = new World();
  const block = world.add(makeBlock('wood', 10.5, 0.5));
  run(world, 1);
  assert.equal(world.terrain.surface(10.5), 0);
  world.explode(10.5, 0.2, 5, 0.7);
  assert.ok(world.terrain.surface(10.5) <= -1, `surface still at ${world.terrain.surface(10.5)}`);
  run(world, 10);
  // Wherever it landed (in the hole or on its rim), it lies on the ground rather than hanging in the air.
  if (!block.removed) {
    assert.ok(Math.hypot(block.vx, block.vy) < 0.5, 'block still moving');
    assert.ok(block.y - world.terrain.surface(block.x) < 1.5, `block floats at ${block.y}`);
  }
});

test('a plane holds its patrol height and turns around at the edge', () => {
  const world = new World();
  const plane = world.add(makePlane('blue', 0, 15));
  let low = Infinity, high = -Infinity;
  for (let i = 0; i < 120 * 12; i++) { world.step(1 / 120); low = Math.min(low, plane.y); high = Math.max(high, plane.y); }
  assert.ok(plane.alive && !plane.removed);
  assert.ok(low > 10 && high < 30, `height ${low}..${high}`);
  assert.ok(plane.x > world.battle.left - 15 && plane.x < world.battle.right + 15, `x=${plane.x}`);
});

test('a homing missile brings a plane down', () => {
  const world = new World();
  const plane = world.add(makePlane('blue', 10, 18));
  const sam = world.add(makeRocket('c75', 40, 2, 0.12, 1.4));
  world.ignite(sam, plane, { burn: 3.5, thrust: 34 });
  let down = false;
  for (let i = 0; i < 120 * 6 && !down; i++) { world.step(1 / 120); down = world.drain().some(e => e.type === 'down'); }
  assert.ok(down, 'plane survived');
});

test('a missile can intercept a rocket in flight', () => {
  const world = new World();
  const rocket = world.add(makeRocket('kh101', 0, 3, 0.1, 0.5));
  world.ignite(rocket, { x: 40, y: 1 });
  const interceptor = world.add(makeRocket('aim9x', 40, 2, 0.12, 2.2));
  world.ignite(interceptor, rocket, { burn: 2.5, thrust: 34 });
  for (let i = 0; i < 120 * 4 && !rocket.removed; i++) world.step(1 / 120);
  assert.ok(rocket.removed, 'rocket was not intercepted');
  assert.ok(Math.abs(rocket.x - 40) > 3 || rocket.y > 3, 'it reached its target first');
});

test('bodies that leave the world are removed, not piled up somewhere', () => {
  const world = new World(-20, 40);
  const block = world.add(makeBlock('stone', 10, 5));
  block.vx = 0; block.vy = 0; block.y = 400;
  world.step(1 / 120);
  assert.ok(block.removed);
});
