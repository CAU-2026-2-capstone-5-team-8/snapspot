// Run: npm test
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { planRegions, REFRESH_ID, type Spot } from './window.ts';

const here = { latitude: 37.5446, longitude: 127.0565 };
/** n spots going east from `here`, 100m apart. */
const line = (group: string, n: number, startM = 100): Spot[] =>
  Array.from({ length: n }, (_, i) => ({
    id: `${group}-${i}`,
    group,
    latitude: here.latitude,
    longitude: here.longitude + ((startM + i * 100) / 88000), // ~88km per degree of longitude at 37.5°N
    radius: 200,
  }));

test('reserves one exit-only refresh region centered on the user', () => {
  const { regions } = planRegions({ here, spots: line('A', 5), slots: 10, refreshRadius: 1000 });
  const refresh = regions.find((r) => r.identifier === REFRESH_ID)!;
  assert.ok(refresh, 'refresh region missing');
  assert.equal(refresh.notifyOnExit, true);
  assert.equal(refresh.notifyOnEnter, false);
  assert.equal(refresh.radius, 1000);
  assert.equal(refresh.latitude, here.latitude);
});

test('never exceeds the slot limit, refresh region included', () => {
  const { regions } = planRegions({ here, spots: [...line('A', 50), ...line('B', 50)], slots: 20, refreshRadius: 1000 });
  assert.equal(regions.length, 20);
});

test('shares slots fairly: one brand with many branches cannot starve the others', () => {
  const spots = [...line('place', 3, 5000), ...line('A', 50), ...line('B', 50, 300)];
  const { byGroup } = planRegions({ here, spots, slots: 11, refreshRadius: 1000 });
  // 10 usable slots, round-robin over 3 groups: place 3 (all of them), A 4, B 3.
  assert.deepEqual(byGroup, { place: 3, A: 4, B: 3 });
});

test('picks the nearest spots within each group', () => {
  const spots = line('A', 10).reverse(); // input order must not matter
  const { regions } = planRegions({ here, spots, slots: 4, refreshRadius: 1000 });
  assert.deepEqual(regions.filter((r) => r.identifier !== REFRESH_ID).map((r) => r.identifier), ['A-0', 'A-1', 'A-2']);
});

test('without a known position: no refresh region, input order kept', () => {
  const { regions } = planRegions({ here: null, spots: line('place', 3), slots: 10, refreshRadius: 1000 });
  assert.deepEqual(regions.map((r) => r.identifier), ['place-0', 'place-1', 'place-2']);
});
