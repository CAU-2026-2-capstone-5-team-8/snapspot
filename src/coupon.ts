/** Coupon rules. Pure, so tests and the simulator can use them. */
import { brandKey } from './brand.ts';
import type { Coupon } from './types.ts';

/** Passing several branches of one brand in a row should alert once, not at every door. */
export const BRAND_COOLDOWN_MS = 3 * 60 * 60 * 1000;

function endOfDay(date: string): number {
  return new Date(`${date}T23:59:59`).getTime();
}

export function isActive(c: Coupon, now = Date.now()): boolean {
  if (c.usedAt) return false;
  if (!c.expiresAt) return true;
  const end = endOfDay(c.expiresAt);
  return Number.isNaN(end) || end >= now;
}

/** Whole days until expiry (0 = expires today), or null when there is no expiry date. */
export function daysLeft(c: Coupon, now = Date.now()): number | null {
  if (!c.expiresAt) return null;
  const end = endOfDay(c.expiresAt);
  if (Number.isNaN(end)) return null;
  return Math.floor((end - now) / 86_400_000);
}

/** Active coupons of one brand, soonest-expiring first. */
export function couponsForBrand(coupons: Coupon[], key: string, now = Date.now()): Coupon[] {
  return coupons
    .filter((c) => brandKey(c.brand) === key && isActive(c, now))
    .sort((a, b) => (a.expiresAt ?? '9999').localeCompare(b.expiresAt ?? '9999'));
}

/** True when this brand alerted recently (through any of its coupons). */
export function brandCoolingDown(coupons: Coupon[], key: string, now = Date.now()): boolean {
  return coupons.some(
    (c) => brandKey(c.brand) === key && c.lastNotifiedAt !== null && now - c.lastNotifiedAt < BRAND_COOLDOWN_MS,
  );
}
