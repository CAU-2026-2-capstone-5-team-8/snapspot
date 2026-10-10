/**
 * App-wide state: saved places, permissions, current position, and the place being added.
 * Screens (map, list, add) read and act through usePlaces().
 */
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, AppState, Platform } from 'react-native';
import { backgroundSupported, syncGeofences } from '../background';
import { distanceMeters, geocodePlace, type Coords } from '../geo';
import { extractPlace, hasGeminiKey } from '../gemini';
import { hasKakaoKey, searchPlace, type Candidate } from '../kakao';
import { notificationsGranted, notifyIfAllowed, setupNotifications } from '../notify';
import { loadPlaces, removePlace, upsertPlace } from '../storage';
import type { Place } from '../types';

export const RADII = [100, 200, 300, 500] as const;
export type Radius = (typeof RADII)[number];

/** Below this, the best Kakao hit is offered as a choice but the device geocoder places the pin. */
const MIN_AUTO_MATCH = 0.4;

export type Draft = {
  /** reading: Gemini is reading the screenshot. locating: searching maps. ready: user confirms. */
  stage: 'reading' | 'locating' | 'ready';
  imageUri: string;
  name: string;
  address: string;
  category: string;
  note: string;
  endDate: string;
  coords: Coords | null;
  isPlace: boolean;
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
  const [watching, setWatching] = useState({ active: 0, total: 0 });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  /** The place saved last, so the map can show where it landed. */
  const [justSavedId, setJustSavedId] = useState<string | null>(null);
  const placesRef = useRef<Place[]>([]);
  placesRef.current = places;

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
  }, [places.length, perms.bg]); // eslint-disable-line react-hooks/exhaustive-deps

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
        };
        setDraft(read);
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

  /** Returns the saved place, or null when there is nothing to save yet. */
  const saveDraft = useCallback(async (): Promise<Place | null> => {
    if (!draft?.coords) return null;
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
    return place;
  }, [draft, resync]);

  const deletePlace = useCallback((p: Place) => {
    Alert.alert(`${p.name} 삭제`, '이 장소의 근처 알림도 함께 꺼져요.', [
      { text: '취소', style: 'cancel' },
      { text: '삭제', style: 'destructive', onPress: async () => setPlaces(await removePlace(p.id)) },
    ]);
  }, []);

  const testNotify = useCallback((p: Place) => notifyIfAllowed(p.id, { force: true }), []);

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
