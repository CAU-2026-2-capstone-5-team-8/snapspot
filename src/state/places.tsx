/**
 * App-wide state: saved places, permissions, current position, and the place being added.
 * Screens (map, list, add) read and act through usePlaces().
 */
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, AppState, Platform } from 'react-native';
import { backgroundSupported, syncGeofences, type WatchSummary } from '../background';
import { distanceMeters, geocodePlace, type Coords } from '../geo';
import { extractPlace, hasGeminiKey } from '../gemini';
import { hasKakaoKey, searchPlace, type Candidate } from '../kakao';
import { notificationsGranted, notifyBranch, notifyIfAllowed, setupNotifications } from '../notify';
import { brandKey } from '../brand';
import { nearbyBranches, type Branch } from '../branches';
import {
  loadCoupons,
  loadPlaces,
  loadWindow,
  removeCoupon,
  removePlace,
  updateCoupons,
  upsertCoupon,
  upsertPlace,
  branchCache,
  type WindowState,
} from '../storage';
import type { Coupon, Place } from '../types';

export const RADII = [100, 200, 300, 500] as const;
export type Radius = (typeof RADII)[number];

/** Below this, the best Kakao hit is offered as a choice but the device geocoder places the pin. */
const MIN_AUTO_MATCH = 0.4;

export type Draft = {
  /** reading: Gemini is reading the screenshot. locating: searching maps. ready: user confirms. */
  stage: 'reading' | 'locating' | 'ready';
  /** What gets saved: a place, or a franchise coupon (alerts at any branch). */
  kind: 'place' | 'coupon';
  brand: string;
  benefit: string;
  imageUri: string;
  name: string;
  address: string;
  category: string;
  note: string;
  endDate: string;
  coords: Coords | null;
  isPlace: boolean;
  /** Coupons only: branches of the brand near the user, for a preview before saving. */
  branches: Branch[] | null;
  /** Kakao result the pin came from, if any. */
  matched: Candidate | null;
  candidates: Candidate[];
  radius: Radius;
};

type Located = Pick<Draft, 'coords' | 'matched' | 'candidates'>;

/** Kakao Local first (handles shop names), device geocoder as fallback. */
async function locate(name: string, address: string, category: string): Promise<Located> {
  let candidates: Candidate[] = [];
  if (hasKakaoKey() && (name || address)) {
    try {
      candidates = (await searchPlace({ name, address, category })).slice(0, 4);
    } catch (e) {
      console.warn('kakao search failed', e);
    }
  }
  const best = candidates[0];
  if (best && best.score >= MIN_AUTO_MATCH) {
    return { coords: { latitude: best.latitude, longitude: best.longitude }, matched: best, candidates };
  }
  return { coords: await geocodePlace(name, address), matched: null, candidates };
}

/** net: Android "Google Location Accuracy". When it is off, the OS drops every geofence. */
export type Perms = { notif: boolean; fg: boolean; bg: boolean; net: boolean };

function usePlacesState() {
  const [places, setPlaces] = useState<Place[]>([]);
  const [here, setHere] = useState<Coords | null>(null);
  const [perms, setPerms] = useState<Perms>({ notif: false, fg: false, bg: false, net: true });
  const [bgOk, setBgOk] = useState(false);
  const [watching, setWatching] = useState<WatchSummary>({ active: 0, byGroup: {}, requests: 0 });
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  /** The plan registered with the OS, for the status screen and branch pins on the map. */
  const [win, setWin] = useState<WindowState | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  /** The place saved last, so the map can show where it landed. */
  const [justSavedId, setJustSavedId] = useState<string | null>(null);
  const placesRef = useRef<Place[]>([]);
  placesRef.current = places;
  const hereRef = useRef<Coords | null>(null);
  hereRef.current = here;

  /** Branches of `brand` around the user, nearest first; null when the position is unknown. */
  const previewBranches = useCallback(async (brand: string): Promise<Branch[] | null> => {
    const at = hereRef.current;
    if (!at || !brand) return null;
    try {
      const { branches } = await nearbyBranches(brand, at, { cache: branchCache });
      return [...branches].sort((a, b) => distanceMeters(at, a) - distanceMeters(at, b));
    } catch (e) {
      console.warn('branch preview failed', e);
      return null;
    }
  }, []);

  const refreshPerms = useCallback(async (ask = false) => {
    const [fg, bg, providers] = await Promise.all([
      Location.getForegroundPermissionsAsync(),
      Location.getBackgroundPermissionsAsync(),
      Location.getProviderStatusAsync().catch(() => null),
    ]);
    const notif = await (ask ? setupNotifications() : notificationsGranted()).catch(() => false);
    const net = Platform.OS !== 'android' || providers?.networkAvailable !== false;
    setPerms({ notif, fg: fg.granted, bg: bg.granted, net });
    setBgOk(await backgroundSupported());
    return { fg: fg.granted, bg: bg.granted };
  }, []);

  const resync = useCallback(
    async (at?: Coords | null) => {
      try {
        setWatching(await syncGeofences(at ?? here));
        setWin(await loadWindow());
      } catch (e) {
        console.warn('geofence sync failed', e);
      }
    },
    [here],
  );

  // Initial load.
  useEffect(() => {
    (async () => {
      setPlaces(await loadPlaces());
      setCoupons(await loadCoupons());
      setWin(await loadWindow());
      await refreshPerms(true);
    })();
  }, [refreshPerms]);

  // Coming back from system settings (location "always", Google Location Accuracy) refreshes status.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') refreshPerms();
    });
    return () => sub.remove();
  }, [refreshPerms]);

  // In-app watcher: works even in Expo Go, and gives live distances.
  useEffect(() => {
    if (!perms.fg) return;
    let sub: Location.LocationSubscription | null = null;
    (async () => {
      sub = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.Balanced, distanceInterval: 25 },
        async (loc) => {
          const c = { latitude: loc.coords.latitude, longitude: loc.coords.longitude };
          setHere(c);
          for (const p of placesRef.current) {
            if (distanceMeters(c, p) <= p.radius) {
              const sent = await notifyIfAllowed(p.id);
              if (sent) setPlaces(await loadPlaces());
            }
          }
        },
      );
    })();
    return () => sub?.remove();
  }, [perms.fg]);

  // Keep OS geofences in sync with saved places.
  useEffect(() => {
    if (perms.bg) resync();
  }, [places.length, perms.bg, here === null]); // eslint-disable-line react-hooks/exhaustive-deps

  const enableNetworkLocation = useCallback(async () => {
    if (Platform.OS !== 'android') return;
    const status = await Location.getProviderStatusAsync().catch(() => null);
    if (status?.networkAvailable === false) {
      await Location.enableNetworkProviderAsync().catch(() => undefined);
    }
    await refreshPerms();
  }, [refreshPerms]);

  const askPermissions = useCallback(async () => {
    const fg = await Location.requestForegroundPermissionsAsync();
    if (fg.granted) {
      const bg = await Location.requestBackgroundPermissionsAsync();
      if (!bg.granted) {
        Alert.alert('백그라운드 위치', '앱을 닫아도 알림을 받으려면 위치 권한을 "항상 허용"으로 바꿔 주세요.');
      }
    }
    await setupNotifications();
    if ((await refreshPerms()).bg) await enableNetworkLocation();
  }, [refreshPerms, enableNetworkLocation]);

  /**
   * Opens the photo picker. Resolves true once a screenshot is chosen, so the caller can show
   * the add screen right away while reading and locating continue in the background.
   */
  const pickScreenshot = useCallback(async (): Promise<boolean> => {
    if (!hasGeminiKey()) {
      Alert.alert('설정이 필요해요', '.env 파일에 EXPO_PUBLIC_GEMINI_API_KEY를 넣고 다시 실행해 주세요.');
      return false;
    }
    const picked = await ImagePicker.launchImageLibraryAsync({ mediaTypes: 'images', quality: 1 });
    if (picked.canceled || !picked.assets?.length) return false;
    const uri = picked.assets[0].uri;
    const base: Draft = {
      stage: 'reading',
      kind: 'place',
      brand: '',
      benefit: '',
      branches: null,
      imageUri: uri,
      name: '',
      address: '',
      category: '',
      note: '',
      endDate: '',
      coords: null,
      isPlace: true,
      matched: null,
      candidates: [],
      radius: 200,
    };
    setDraft(base);
    (async () => {
      try {
        const ctx = ImageManipulator.manipulate(uri);
        ctx.resize({ width: 1024 });
        const rendered = await ctx.renderAsync();
        const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, base64: true, compress: 0.8 });
        const ex = await extractPlace(saved.base64 ?? '');
        const read: Draft = {
          ...base,
          stage: 'locating',
          imageUri: saved.uri,
          name: ex.name,
          address: ex.address,
          category: ex.category,
          note: ex.note,
          endDate: ex.endDate ?? '',
          isPlace: ex.isPlace,
          kind: ex.kind,
          brand: ex.brand,
          benefit: ex.benefit,
        };
        setDraft(read);
        if (ex.kind === 'coupon') {
          const branches = await previewBranches(ex.brand);
          setDraft((d) => (d && d.imageUri === read.imageUri ? { ...d, branches, stage: 'ready' } : d));
          return;
        }
        const located: Located = ex.isPlace
          ? await locate(ex.name, ex.address, ex.category)
          : { coords: null, matched: null, candidates: [] };
        setDraft((d) => (d && d.imageUri === read.imageUri ? { ...d, ...located, stage: 'ready' } : d));
      } catch (e) {
        setDraft((d) => (d ? { ...d, stage: 'ready' } : d));
        Alert.alert('스크린샷을 읽지 못했어요', e instanceof Error ? e.message : String(e));
      }
    })();
    return true;
  }, []);

  const updateDraft = useCallback((patch: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...patch } : d)), []);

  const regeocode = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    const located = await locate(draft.name, draft.address, draft.category);
    updateDraft(located);
    setBusy(false);
    if (!located.coords) Alert.alert('위치를 못 찾았어요', '주소를 더 구체적으로 고치거나 "지금 위치로"를 눌러 주세요.');
  }, [draft, updateDraft]);

  const pickCandidate = useCallback(
    (c: Candidate) =>
      setDraft((d) =>
        d
          ? {
              ...d,
              name: c.source === 'keyword' ? c.name : d.name,
              address: c.address,
              coords: { latitude: c.latitude, longitude: c.longitude },
              matched: c,
            }
          : d,
      ),
    [],
  );

  const pinToHere = useCallback(async () => {
    const loc = here ?? (await Location.getCurrentPositionAsync({}).then((l) => l.coords).catch(() => null));
    if (loc) updateDraft({ coords: { latitude: loc.latitude, longitude: loc.longitude }, matched: null });
  }, [here, updateDraft]);

  /** Saves the draft as a place or a coupon. Returns what was saved, or null when not ready. */
  const saveDraft = useCallback(async (): Promise<{ kind: 'place' | 'coupon'; id: string } | null> => {
    if (!draft) return null;
    if (draft.kind === 'coupon') {
      if (!draft.brand.trim()) return null;
      const coupon: Coupon = {
        id: `c_${Date.now().toString(36)}`,
        brand: draft.brand.trim(),
        benefit: draft.benefit.trim() || '쿠폰',
        expiresAt: /^\d{4}-\d{2}-\d{2}$/.test(draft.endDate) ? draft.endDate : null,
        imageUri: draft.imageUri,
        createdAt: Date.now(),
        usedAt: null,
        lastNotifiedAt: null,
      };
      setCoupons(await upsertCoupon(coupon));
      setDraft(null);
      await resync();
      return { kind: 'coupon', id: coupon.id };
    }
    if (!draft.coords) return null;
    const place: Place = {
      id: `p_${Date.now().toString(36)}`,
      name: draft.name || '이름 없는 장소',
      address: draft.address,
      category: draft.category || '기타',
      note: draft.note,
      endDate: /^\d{4}-\d{2}-\d{2}$/.test(draft.endDate) ? draft.endDate : null,
      latitude: draft.coords.latitude,
      longitude: draft.coords.longitude,
      radius: draft.radius,
      imageUri: draft.imageUri,
      kakaoId: draft.matched?.kakaoId ?? null,
      placeUrl: draft.matched?.placeUrl ?? null,
      createdAt: Date.now(),
      lastNotifiedAt: null,
    };
    setPlaces(await upsertPlace(place));
    setJustSavedId(place.id);
    setDraft(null);
    await resync();
    return { kind: 'place', id: place.id };
  }, [draft, resync]);

  /** Switching a draft between place and coupon (Gemini can guess wrong). */
  const setDraftKind = useCallback(
    async (kind: 'place' | 'coupon') => {
      if (!draft || draft.kind === kind) return;
      if (kind === 'coupon') {
        const brand = draft.brand || draft.name;
        updateDraft({ kind, brand, branches: null });
        updateDraft({ branches: await previewBranches(brand) });
      } else {
        updateDraft({ kind });
        if (!draft.coords) updateDraft(await locate(draft.name, draft.address, draft.category));
      }
    },
    [draft, updateDraft, previewBranches],
  );

  const refreshBranchPreview = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    updateDraft({ branches: await previewBranches(draft.brand) });
    setBusy(false);
  }, [draft, updateDraft, previewBranches]);

  const markCouponUsed = useCallback(async (c: Coupon) => {
    setCoupons(await updateCoupons([c.id], { usedAt: Date.now() }));
  }, []);

  /** Asks first; `onDeleted` runs after removal (the coupon screen closes itself with it). */
  const deleteCoupon = useCallback((c: Coupon, onDeleted?: () => void) => {
    Alert.alert(`${c.brand} 쿠폰 삭제`, '이 쿠폰의 지점 알림도 함께 꺼져요.', [
      { text: '취소', style: 'cancel' },
      {
        text: '삭제',
        style: 'destructive',
        onPress: async () => {
          onDeleted?.();
          setCoupons(await removeCoupon(c.id));
        },
      },
    ]);
  }, []);

  /** Show the alert a branch would send, using the nearest registered branch of this brand. */
  const testCoupon = useCallback(
    (c: Coupon) => {
      const key = brandKey(c.brand);
      const id = Object.keys(win?.branches ?? {}).find((k) => k.startsWith(`b|${key}|`)) ?? `b|${key}|test`;
      return notifyBranch(id, { force: true });
    },
    [win],
  );

  const deletePlace = useCallback((p: Place) => {
    Alert.alert(`${p.name} 삭제`, '이 장소의 근처 알림도 함께 꺼져요.', [
      { text: '취소', style: 'cancel' },
      { text: '삭제', style: 'destructive', onPress: async () => setPlaces(await removePlace(p.id)) },
    ]);
  }, []);

  const testNotify = useCallback((p: Place) => notifyIfAllowed(p.id, { force: true }), []);

  // Used and resynced coupons change what the window registers.
  useEffect(() => {
    if (perms.bg) resync();
  }, [coupons.filter((c) => !c.usedAt).length]); // eslint-disable-line react-hooks/exhaustive-deps

  const sorted = useMemo(() => {
    if (!here) return places;
    return [...places].sort((a, b) => distanceMeters(here, a) - distanceMeters(here, b));
  }, [places, here]);

  const alertsOn = perms.notif && perms.fg && perms.bg && bgOk && perms.net;

  return {
    places: sorted,
    here,
    perms,
    bgOk,
    alertsOn,
    watching: perms.net ? watching.active : 0,
    watchSummary: watching,
    win,
    coupons,
    setDraftKind,
    refreshBranchPreview,
    markCouponUsed,
    deleteCoupon,
    testCoupon,
    draft,
    busy,
    justSavedId,
    clearJustSaved: () => setJustSavedId(null),
    askPermissions,
    enableNetworkLocation,
    pickScreenshot,
    updateDraft,
    cancelDraft: () => setDraft(null),
    regeocode,
    pickCandidate,
    pinToHere,
    saveDraft,
    deletePlace,
    testNotify,
  };
}

type PlacesState = ReturnType<typeof usePlacesState>;
const Ctx = createContext<PlacesState | null>(null);

export function PlacesProvider({ children }: { children: ReactNode }) {
  return <Ctx.Provider value={usePlacesState()}>{children}</Ctx.Provider>;
}

export function usePlaces(): PlacesState {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePlaces must be used inside PlacesProvider');
  return v;
}
