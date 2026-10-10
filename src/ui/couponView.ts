/** Display helpers shared by the coupon wallet and the coupon screen. */
import { brandKey } from '../brand';
import { distanceMeters, type Coords } from '../geo';
import type { WindowState } from '../storage';
import type { Coupon } from '../types';

/** Nearest registered branch of the coupon's brand, from the current geofence plan. */
export function nearestBranch(c: Coupon, win: WindowState | null, here: Coords | null) {
  if (!win || !here) return null;
  const key = brandKey(c.brand);
  let best: { name: string; distance: number } | null = null;
  for (const b of Object.values(win.branches)) {
    if (b.brandKey !== key) continue;
    const d = distanceMeters(here, b);
    if (!best || d < best.distance) best = { name: b.name, distance: d };
  }
  return best;
}

export function expiresLabel(c: Coupon): string | null {
  if (!c.expiresAt) return null;
  const [y, m, d] = c.expiresAt.split('-');
  return `${y}년 ${Number(m)}월 ${Number(d)}일까지`;
}
