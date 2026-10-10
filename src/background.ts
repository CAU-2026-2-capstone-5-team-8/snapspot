/**
 * Background work. This file must be imported from index.ts BEFORE the app registers,
 * so the tasks exist when Android/iOS wakes the app up in the background.
 *
 * Geofences come from the sliding window (window.ts): saved places and nearby franchise
 * branches share the OS limit, plus one exit-only "refresh" region around the user.
 * Leaving it re-plans around the new position.
 */
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { Platform } from 'react-native';
import { brandKey } from './brand';
import { nearbyBranches } from './branches';
import { isActive } from './coupon';
import type { Coords } from './geo';
import { notifyBranch, notifyIfAllowed, suppressReason } from './notify';
import { branchCache, loadCoupons, loadPlaces, saveWindow, type WindowState } from './storage';
import { planRegions, REFRESH_ID, type Spot } from './window';

export const GEOFENCE_TASK = 'snapspot-geofence';
/** Earlier versions tracked location continuously under this task; stopped if still running. */
const LEGACY_REFRESH_TASK = 'snapspot-refresh-nearest';

/** OS limits: iOS monitors at most 20 regions, Android 100. Keep a margin on Android. */
export const MAX_REGIONS = Platform.OS === 'ios' ? 20 : 90;
/** Branch alert radius. Geofence alerts arrive minutes late, so err on the wide side. */
export const BRANCH_RADIUS_M = 200;
/** Leaving this circle around the user triggers a re-plan. */
export const REFRESH_RADIUS_M = 1000;

type GeofenceData = { eventType: Location.GeofencingEventType; region: Location.LocationRegion };

export const isBranchRegion = (id: string) => id.startsWith('b|');

try {
  TaskManager.defineTask<GeofenceData>(GEOFENCE_TASK, async ({ data, error }) => {
    if (error || !data?.region?.identifier) return;
    const id = data.region.identifier;
    if (id === REFRESH_ID) {
      if (data.eventType !== Location.GeofencingEventType.Exit) return;
      const loc =
        (await Location.getLastKnownPositionAsync({ maxAge: 60_000 }).catch(() => null)) ??
        (await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }).catch(() => null));
      if (loc) await syncGeofences({ latitude: loc.coords.latitude, longitude: loc.coords.longitude });
      return;
    }
    if (data.eventType !== Location.GeofencingEventType.Enter) return;
    if (isBranchRegion(id)) await notifyBranch(id);
    else await notifyIfAllowed(id);
  });
} catch {
  // Expo Go on Android has no TaskManager; the in-app watcher still works there.
}

export type WatchSummary = { active: number; byGroup: Record<string, number>; requests: number };

/** Re-plan geofences around `here` and register them with the OS. */
export async function syncGeofences(here?: Coords | null): Promise<WatchSummary> {
  const at = here ?? null;
  const places = (await loadPlaces()).filter((p) => suppressReason(p) !== '운영 종료');
  const coupons = (await loadCoupons()).filter((c) => isActive(c));

  const spots: Spot[] = places.map((p) => ({
    id: p.id,
    group: 'place',
    latitude: p.latitude,
    longitude: p.longitude,
    radius: p.radius,
  }));
  const branches: WindowState['branches'] = {};
  let requests = 0;
  if (at) {
    const brands = new Map(coupons.map((c) => [brandKey(c.brand), c.brand]));
    for (const [key, brand] of brands) {
      try {
        const r = await nearbyBranches(brand, at, { cache: branchCache });
        requests += r.requests;
        for (const b of r.branches) {
          const id = `b|${key}|${b.kakaoId}`;
          branches[id] = { name: b.name, brandKey: key, latitude: b.latitude, longitude: b.longitude };
          spots.push({ id, group: key, latitude: b.latitude, longitude: b.longitude, radius: BRANCH_RADIUS_M });
        }
      } catch (e) {
        console.warn('branch search failed', brand, e);
      }
    }
  }

  const plan = planRegions({ here: at, spots, slots: MAX_REGIONS, refreshRadius: REFRESH_RADIUS_M });
  const registered = new Set(plan.regions.map((r) => r.identifier));
  await saveWindow({
    at: Date.now(),
    here: at,
    slots: MAX_REGIONS,
    byGroup: plan.byGroup,
    branches: Object.fromEntries(Object.entries(branches).filter(([id]) => registered.has(id))),
    requests,
  });

  const summary = { active: plan.regions.filter((r) => r.identifier !== REFRESH_ID).length, byGroup: plan.byGroup, requests };
  if (!(await TaskManager.isAvailableAsync().catch(() => false))) return summary;

  if (plan.regions.length === 0) {
    if (await Location.hasStartedGeofencingAsync(GEOFENCE_TASK).catch(() => false)) {
      await Location.stopGeofencingAsync(GEOFENCE_TASK);
    }
  } else {
    await Location.startGeofencingAsync(GEOFENCE_TASK, plan.regions);
  }
  if (await Location.hasStartedLocationUpdatesAsync(LEGACY_REFRESH_TASK).catch(() => false)) {
    await Location.stopLocationUpdatesAsync(LEGACY_REFRESH_TASK).catch(() => undefined);
  }
  return summary;
}

export async function backgroundSupported(): Promise<boolean> {
  return TaskManager.isAvailableAsync().catch(() => false);
}
