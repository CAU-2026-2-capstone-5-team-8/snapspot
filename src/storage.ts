import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Coupon, Place } from './types';
import type { BranchCache } from './branches';

const KEY = 'snapspot.places.v1';

export async function loadPlaces(): Promise<Place[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Place[]) : [];
  } catch {
    return [];
  }
}

export async function savePlaces(places: Place[]): Promise<void> {
  await AsyncStorage.setItem(KEY, JSON.stringify(places));
}

export async function upsertPlace(place: Place): Promise<Place[]> {
  const places = await loadPlaces();
  const next = [place, ...places.filter((p) => p.id !== place.id)];
  await savePlaces(next);
  return next;
}

export async function removePlace(id: string): Promise<Place[]> {
  const next = (await loadPlaces()).filter((p) => p.id !== id);
  await savePlaces(next);
  return next;
}

export async function markNotified(id: string, at: number): Promise<void> {
  const places = await loadPlaces();
  await savePlaces(places.map((p) => (p.id === id ? { ...p, lastNotifiedAt: at } : p)));
}

const COUPON_KEY = 'snapspot.coupons.v1';

export async function loadCoupons(): Promise<Coupon[]> {
  try {
    const raw = await AsyncStorage.getItem(COUPON_KEY);
    return raw ? (JSON.parse(raw) as Coupon[]) : [];
  } catch {
    return [];
  }
}

export async function saveCoupons(coupons: Coupon[]): Promise<void> {
  await AsyncStorage.setItem(COUPON_KEY, JSON.stringify(coupons));
}

export async function upsertCoupon(coupon: Coupon): Promise<Coupon[]> {
  const next = [coupon, ...(await loadCoupons()).filter((c) => c.id !== coupon.id)];
  await saveCoupons(next);
  return next;
}

export async function updateCoupons(ids: string[], patch: Partial<Coupon>): Promise<Coupon[]> {
  const next = (await loadCoupons()).map((c) => (ids.includes(c.id) ? { ...c, ...patch } : c));
  await saveCoupons(next);
  return next;
}

export async function removeCoupon(id: string): Promise<Coupon[]> {
  const next = (await loadCoupons()).filter((c) => c.id !== id);
  await saveCoupons(next);
  return next;
}

/** Branch search results, cached per grid cell (see branches.ts). Survives headless wake-ups. */
const BRANCH_PREFIX = 'snapspot.branches.';

export const branchCache: BranchCache = {
  async get(key) {
    try {
      const raw = await AsyncStorage.getItem(BRANCH_PREFIX + key);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  },
  async set(key, value) {
    await AsyncStorage.setItem(BRANCH_PREFIX + key, JSON.stringify(value));
  },
};

/** The geofence plan currently registered with the OS: for alerts woken in the background and the status screen. */
export type WindowState = {
  at: number;
  here: { latitude: number; longitude: number } | null;
  slots: number;
  /** Regions registered per group: "place" or a brand key. */
  byGroup: Record<string, number>;
  /** Branch regions by identifier, so an Enter event can name the branch. */
  branches: Record<string, { name: string; brandKey: string; latitude: number; longitude: number }>;
  /** Kakao requests made by this plan (0 when every brand came from cache). */
  requests: number;
};

const WINDOW_KEY = 'snapspot.window.v1';

export async function loadWindow(): Promise<WindowState | null> {
  try {
    const raw = await AsyncStorage.getItem(WINDOW_KEY);
    return raw ? (JSON.parse(raw) as WindowState) : null;
  } catch {
    return null;
  }
}

export async function saveWindow(w: WindowState): Promise<void> {
  await AsyncStorage.setItem(WINDOW_KEY, JSON.stringify(w));
}
