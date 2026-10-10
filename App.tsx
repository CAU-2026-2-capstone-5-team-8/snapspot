import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AppState, Linking, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { backgroundSupported, syncGeofences } from './src/background';
import { distanceMeters, formatDistance, geocodePlace, type Coords } from './src/geo';
import { extractPlace, hasGeminiKey } from './src/gemini';
import { hasKakaoKey, searchPlace, type Candidate } from './src/kakao';
import { notificationsGranted, notifyIfAllowed, setupNotifications, suppressReason } from './src/notify';
import { loadPlaces, removePlace, upsertPlace } from './src/storage';
import type { Place } from './src/types';
import {
  Button,
  CandidateRow,
  Field,
  MatchBox,
  Notice,
  PlaceCard,
  Segmented,
  Shot,
  WatchStatus,
} from './src/ui/components';
import { color, font, space, type } from './src/ui/theme';

const RADII = [100, 200, 300, 500] as const;
/** Below this, the best Kakao hit is offered as a choice but the device geocoder places the pin. */
const MIN_AUTO_MATCH = 0.4;

type Draft = {
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
type Perms = { notif: boolean; fg: boolean; bg: boolean; net: boolean };

export default function App() {
  return (
    <SafeAreaProvider>
      <Home />
    </SafeAreaProvider>
  );
}

function Home() {
  const [places, setPlaces] = useState<Place[]>([]);
  const [here, setHere] = useState<Coords | null>(null);
  const [perms, setPerms] = useState<Perms>({ notif: false, fg: false, bg: false, net: true });
  const [bgOk, setBgOk] = useState(false);
  const [watching, setWatching] = useState({ active: 0, total: 0 });
  const [radius, setRadius] = useState<(typeof RADII)[number]>(200);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
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

  const resync = useCallback(async (at?: Coords | null) => {
    try {
      setWatching(await syncGeofences(at ?? here));
    } catch (e) {
      console.warn('geofence sync failed', e);
    }
  }, [here]);

  // Initial load.
  useEffect(() => {
    (async () => {
      setPlaces(await loadPlaces());
      await refreshPerms(true);
    })();
  }, [refreshPerms]);

  // Coming back from system settings (location "always", Google Location Accuracy) updates the chips.
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

  const askPermissions = async () => {
    const fg = await Location.requestForegroundPermissionsAsync();
    if (fg.granted) {
      const bg = await Location.requestBackgroundPermissionsAsync();
      if (!bg.granted) {
        Alert.alert(
          '백그라운드 위치',
          '앱이 꺼져 있을 때도 알림을 받으려면 위치 권한을 "항상 허용"으로 바꿔 주세요.',
        );
      }
    }
    await setupNotifications();
    if ((await refreshPerms()).bg) await enableNetworkLocation();
  };

  const enableNetworkLocation = async () => {
    if (Platform.OS !== 'android') return;
    const status = await Location.getProviderStatusAsync().catch(() => null);
    if (status?.networkAvailable === false) {
      await Location.enableNetworkProviderAsync().catch(() => undefined);
    }
    await refreshPerms();
  };

  const pickScreenshot = async () => {
    if (!hasGeminiKey()) {
      Alert.alert('설정 필요', '.env 파일에 EXPO_PUBLIC_GEMINI_API_KEY를 넣고 다시 실행해 주세요.');
      return;
    }
    const picked = await ImagePicker.launchImageLibraryAsync({ mediaTypes: 'images', quality: 1 });
    if (picked.canceled || !picked.assets?.length) return;
    const uri = picked.assets[0].uri;
    try {
      setBusy('스크린샷을 읽는 중…');
      const ctx = ImageManipulator.manipulate(uri);
      ctx.resize({ width: 1024 });
      const rendered = await ctx.renderAsync();
      const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, base64: true, compress: 0.8 });
      const ex = await extractPlace(saved.base64 ?? '');
      setBusy('지도에서 장소를 찾는 중…');
      const located: Located = ex.isPlace
        ? await locate(ex.name, ex.address, ex.category)
        : { coords: null, matched: null, candidates: [] };
      setDraft({
        imageUri: saved.uri,
        name: ex.name,
        address: ex.address,
        category: ex.category,
        note: ex.note,
        endDate: ex.endDate ?? '',
        isPlace: ex.isPlace,
        ...located,
      });
    } catch (e) {
      Alert.alert('분석 실패', e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const regeocode = async () => {
    if (!draft) return;
    setBusy('다시 찾는 중…');
    const located = await locate(draft.name, draft.address, draft.category);
    setDraft({ ...draft, ...located });
    setBusy(null);
    if (!located.coords) Alert.alert('위치를 못 찾았어요', '주소를 더 구체적으로 고치거나 "현재 위치로"를 눌러 주세요.');
  };

  const pickCandidate = (c: Candidate) => {
    if (!draft) return;
    setDraft({
      ...draft,
      name: c.source === 'keyword' ? c.name : draft.name,
      address: c.address,
      coords: { latitude: c.latitude, longitude: c.longitude },
      matched: c,
    });
  };

  const useHere = async () => {
    if (!draft) return;
    const loc = here ?? (await Location.getCurrentPositionAsync({}).then((l) => l.coords).catch(() => null));
    if (loc) setDraft({ ...draft, coords: { latitude: loc.latitude, longitude: loc.longitude }, matched: null });
  };

  const saveDraft = async () => {
    if (!draft?.coords) return;
    const place: Place = {
      id: `p_${Date.now().toString(36)}`,
      name: draft.name || '이름 없는 장소',
      address: draft.address,
      category: draft.category || '기타',
      note: draft.note,
      endDate: /^\d{4}-\d{2}-\d{2}$/.test(draft.endDate) ? draft.endDate : null,
      latitude: draft.coords.latitude,
      longitude: draft.coords.longitude,
      radius,
      imageUri: draft.imageUri,
      kakaoId: draft.matched?.kakaoId ?? null,
      placeUrl: draft.matched?.placeUrl ?? null,
      createdAt: Date.now(),
      lastNotifiedAt: null,
    };
    const next = await upsertPlace(place);
    setPlaces(next);
    setDraft(null);
    await resync();
  };

  const deletePlace = (p: Place) => {
    Alert.alert(`${p.name} 삭제`, '이 장소의 근처 알림도 함께 꺼져요.', [
      { text: '취소', style: 'cancel' },
      { text: '삭제', style: 'destructive', onPress: async () => setPlaces(await removePlace(p.id)) },
    ]);
  };

  const sorted = useMemo(() => {
    if (!here) return places;
    return [...places].sort((a, b) => distanceMeters(here, a) - distanceMeters(here, b));
  }, [places, here]);

  const alertsOn = perms.notif && perms.fg && perms.bg && bgOk && perms.net;
  const otherCandidates = draft ? draft.candidates.filter((c) => c !== draft.matched).slice(0, 3) : [];

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={s.body} keyboardShouldPersistTaps="handled">
        <View style={s.header}>
          <Text style={type.hero} accessibilityRole="header">
            SnapSpot
          </Text>
          <Text style={[type.body, { color: color.muted }]}>찍어 둔 곳 근처에 가면 알려 드려요</Text>
        </View>

        <WatchStatus on={alertsOn} watching={perms.net ? watching.active : 0} />

        {!(perms.notif && perms.fg && perms.bg) && (
          <Notice action={{ label: '권한 설정하기', onPress: askPermissions }}>
            앱을 닫아도 알림을 받으려면 알림 권한과 위치 &quot;항상 허용&quot;이 필요해요.
          </Notice>
        )}
        {perms.bg && bgOk && !perms.net && (
          <Notice action={{ label: '위치 정확도 켜기', onPress: enableNetworkLocation }}>
            휴대폰의 &quot;Google 위치 정확도&quot;가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요.
          </Notice>
        )}
        {!bgOk && (
          <Notice>지금은 앱을 열어 둔 동안만 알려 드려요. 앱을 닫아도 오는 알림은 설치용 빌드(APK)에서 동작해요.</Notice>
        )}

        {!draft && (
          <Button
            label={busy ?? '스크린샷에서 장소 추가'}
            onPress={pickScreenshot}
            busy={!!busy}
            style={{ marginTop: space.s }}
          />
        )}

        {draft && (
          <View style={s.sheet}>
            <View style={s.sheetHead}>
              <Text style={[type.title, { flex: 1 }]}>새 장소 확인</Text>
              <Button label="취소" onPress={() => setDraft(null)} kind="quiet" />
            </View>

            {!draft.isPlace && (
              <Notice>이 이미지에서는 장소를 찾기 어려웠어요. 이름과 주소를 직접 고쳐 주세요.</Notice>
            )}

            <View style={s.draftTop}>
              <Shot uri={draft.imageUri} width={96} height={128} />
              <View style={{ flex: 1, gap: space.m }}>
                <Field label="장소 이름" value={draft.name} onChange={(v) => setDraft({ ...draft, name: v })} large />
                <Field label="분류" value={draft.category} onChange={(v) => setDraft({ ...draft, category: v })} />
              </View>
            </View>
            <Field label="메모" value={draft.note} onChange={(v) => setDraft({ ...draft, note: v })} />
            <Field
              label="주소나 위치 단서"
              value={draft.address}
              onChange={(v) => setDraft({ ...draft, address: v })}
            />
            <Field
              label="종료일 (팝업·전시만)"
              value={draft.endDate}
              onChange={(v) => setDraft({ ...draft, endDate: v })}
              placeholder="2026-10-31"
            />

            <View style={{ gap: space.s }}>
              <Text style={type.label}>지도에서 찾은 위치</Text>
              <MatchBox matched={draft.matched} hasCoords={!!draft.coords} />
              {draft.coords && here && (
                <Text style={type.meta}>여기서 {formatDistance(distanceMeters(here, draft.coords))}</Text>
              )}
              {otherCandidates.length > 0 && (
                <View style={s.candidates}>
                  <Text style={[type.label, s.candidatesHead]}>다른 후보</Text>
                  {otherCandidates.map((c) => (
                    <CandidateRow key={`${c.source}-${c.kakaoId ?? c.address}`} c={c} onPress={() => pickCandidate(c)} />
                  ))}
                </View>
              )}
              <View style={s.row}>
                <Button label="주소로 다시 찾기" onPress={regeocode} kind="secondary" busy={!!busy} style={{ flex: 1 }} />
                <Button label="지금 위치로" onPress={useHere} kind="secondary" style={{ flex: 1 }} />
              </View>
            </View>

            <View style={{ gap: space.s }}>
              <Text style={type.label}>이만큼 가까워지면 알려 드려요</Text>
              <Segmented options={RADII} value={radius} onChange={setRadius} format={(r) => `${r}m`} />
            </View>

            <Button label="저장" onPress={saveDraft} disabled={!draft.coords} />
          </View>
        )}

        <View style={s.listHead}>
          <Text style={type.title}>저장한 곳</Text>
          {places.length > 0 && (
            <Text style={type.meta}>
              {places.length}곳{here ? ', 가까운 순' : ''}
            </Text>
          )}
        </View>

        {sorted.length === 0 ? (
          <View style={s.empty}>
            <Text style={[type.body, { fontFamily: font.medium }]}>아직 저장한 곳이 없어요</Text>
            <Text style={type.meta}>
              인스타 맛집이나 팝업 포스터 스크린샷을 추가하면 장소를 찾아 저장하고, 근처에 가면 알려 드려요.
            </Text>
          </View>
        ) : (
          sorted.map((p) => (
            <PlaceCard
              key={p.id}
              place={p}
              distance={here ? distanceMeters(here, p) : null}
              status={suppressReason(p)}
              onTest={() => notifyIfAllowed(p.id, { force: true })}
              onMap={p.placeUrl ? () => Linking.openURL(p.placeUrl!) : undefined}
              onDelete={() => deletePlace(p)}
            />
          ))
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: color.paper },
  body: { paddingHorizontal: space.l + 4, paddingTop: space.xl, paddingBottom: 64, gap: space.l },
  header: { gap: space.xs },
  sheet: {
    backgroundColor: color.surface,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: color.line,
    padding: space.l + 4,
    gap: space.l + 4,
  },
  sheetHead: { flexDirection: 'row', alignItems: 'center', marginRight: -space.s },
  draftTop: { flexDirection: 'row', gap: space.m, alignItems: 'flex-start' },
  candidates: { borderWidth: 1, borderColor: color.line, borderRadius: 12, overflow: 'hidden' },
  candidatesHead: { paddingHorizontal: space.m, paddingVertical: space.s },
  row: { flexDirection: 'row', gap: space.s },
  listHead: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: space.l },
  empty: {
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.line,
    borderRadius: 16,
    padding: space.xl,
    gap: space.s,
  },
});
