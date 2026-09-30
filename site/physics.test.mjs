import assert from 'node:assert/strict';
import test from 'node:test';
import { makeRocket, stepRocket } from './physics.mjs';

test('each model creates its own selectable body', () => {
  const rocket = makeRocket('c75', 150, 100, { naturalWidth: 350, naturalHeight: 70 });
  assert.equal(rocket.width, 177);
  assert.equal(rocket.height, 35.4);
});

test('a winged missile loses less height than a small unguided rocket', () => {
  const image = { naturalWidth: 350, naturalHeight: 70 };
  const winged = makeRocket('kh101', 100, 120, image);
  const small = makeRocket('s8', 100, 120, image);
  winged.vx = small.vx = 420;
  for (let i = 0; i < 30; i++) {
    stepRocket(winged, 1 / 60, { width: 2000, height: 1000 });
    stepRocket(small, 1 / 60, { width: 2000, height: 1000 });
  }
  assert.ok(winged.y < small.y, `winged=${winged.y}, small=${small.y}`);
});

test('leaving the field respawns a rocket on the left', () => {
  const rocket = makeRocket('vihr', 900, 100);
  rocket.vx = 300;
  stepRocket(rocket, 1 / 60, { width: 500, height: 400 });
  assert.ok(rocket.x < 0);
  assert.ok(rocket.vx > 0);
});

test('dragged rockets stay exactly under the pointer', () => {
  const rocket = makeRocket('aim9x', 90, 90);
  rocket.held = true;
  rocket.vx = 500;
  stepRocket(rocket, 1 / 30, { width: 500, height: 400 });
  assert.deepEqual([rocket.x, rocket.y], [90, 90]);
});
