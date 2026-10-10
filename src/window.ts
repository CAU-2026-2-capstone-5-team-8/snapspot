/**
 * Sliding geofence window. The OS watches at most ~100 regions (Android) / 20 (iOS), but a
 * franchise coupon can match hundreds of branches. So we register only the nearest spots, shared
 * fairly between groups (saved places, each brand), plus one large exit-only "refresh" region
 * around the user. Leaving it wakes the app to re-plan around the new position — no continuous
 * location tracking needed. Pure, so the simulator and tests can drive it.
 */
import { distanceMeters, type Coords } from './distance.ts';

export const REFRESH_ID = 'refresh';

/** Something worth watching: a saved place (group "place") or a branch (group = brand key). */
export type Spot = Coords & { id: string; group: string; radius: number };

export type Region = {
  identifier: string;
  latitude: number;
  longitude: number;
  radius: number;
  notifyOnEnter: boolean;
  notifyOnExit: boolean;
};

export type Plan = { regions: Region[]; byGroup: Record<string, number> };

export function planRegions(opts: {
  here: Coords | null;
  spots: Spot[];
  /** Total regions the OS allows us; the refresh region counts toward it. */
  slots: number;
  refreshRadius: number;
}): Plan {
  const { here, spots, slots, refreshRadius } = opts;
  const regions: Region[] = [];
  if (here && slots > 0) {
    regions.push({
      identifier: REFRESH_ID,
      latitude: here.latitude,
      longitude: here.longitude,
      radius: refreshRadius,
      notifyOnEnter: false,
      notifyOnExit: true,
    });
  }

  // One queue per group, nearest first (input order when the position is unknown).
  const queues = new Map<string, Spot[]>();
  for (const s of spots) queues.set(s.group, [...(queues.get(s.group) ?? []), s]);
  if (here) {
    for (const q of queues.values()) q.sort((a, b) => distanceMeters(here, a) - distanceMeters(here, b));
  }

  // Round-robin across groups so one brand with many branches cannot take every slot.
  const byGroup: Record<string, number> = {};
  const order = [...queues.keys()];
  let progressed = true;
  while (regions.length < slots && progressed) {
    progressed = false;
    for (const g of order) {
      if (regions.length >= slots) break;
      const next = queues.get(g)!.shift();
      if (!next) continue;
      progressed = true;
      byGroup[g] = (byGroup[g] ?? 0) + 1;
      regions.push({
        identifier: next.id,
        latitude: next.latitude,
        longitude: next.longitude,
        radius: next.radius,
        notifyOnEnter: true,
        notifyOnExit: false,
      });
    }
  }
  return { regions, byGroup };
}
