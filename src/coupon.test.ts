// Run: npm test
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BRAND_COOLDOWN_MS, brandCoolingDown, couponsForBrand, daysLeft, isActive } from './coupon.ts';
import type { Coupon } from './types.ts';

const now = new Date('2026-10-10T12:00:00').getTime();
const c = (over: Partial<Coupon>): Coupon => ({
  id: 'c',
  brand: '스타벅스',
  benefit: '아메리카노',
  expiresAt: null,
  imageUri: null,
  createdAt: 0,
  usedAt: null,
  lastNotifiedAt: null,
  ...over,
});

test('a coupon stays active through its last day, then expires', () => {
  assert.equal(isActive(c({ expiresAt: '2026-10-10' }), now), true);
  assert.equal(isActive(c({ expiresAt: '2026-10-09' }), now), false);
  assert.equal(isActive(c({ usedAt: 1 }), now), false);
  assert.equal(isActive(c({}), now), true);
});

test('daysLeft counts whole days, 0 on the last day', () => {
  assert.equal(daysLeft(c({ expiresAt: '2026-10-10' }), now), 0);
  assert.equal(daysLeft(c({ expiresAt: '2026-10-31' }), now), 21);
  assert.equal(daysLeft(c({}), now), null);
});

test('couponsForBrand matches across spacing and puts the soonest expiry first', () => {
  const list = [
    c({ id: 'late', brand: '스타 벅스', expiresAt: '2026-12-01' }),
    c({ id: 'soon', expiresAt: '2026-10-20' }),
    c({ id: 'other', brand: '올리브영' }),
    c({ id: 'used', usedAt: 1 }),
  ];
  assert.deepEqual(couponsForBrand(list, '스타벅스', now).map((x) => x.id), ['soon', 'late']);
});

test('one alert for a brand cools down all its coupons', () => {
  const list = [c({ id: 'a', lastNotifiedAt: now - 1000 }), c({ id: 'b' })];
  assert.equal(brandCoolingDown(list, '스타벅스', now), true);
  assert.equal(brandCoolingDown(list, '스타벅스', now + BRAND_COOLDOWN_MS), false);
  assert.equal(brandCoolingDown(list, '올리브영', now), false);
});
