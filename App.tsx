import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { backgroundSupported, MAX_REGIONS, syncGeofences } from './src/background';
import { distanceMeters, formatDistance, geocodePlace, type Coords } from './src/geo';
import { extractPlace, hasGeminiKey } from './src/gemini';
import { notifyIfAllowed, setupNotifications, suppressReason } from './src/notify';
import { loadPlaces, removePlace, upsertPlace } from './src/storage';
import type { Place } from './src/types';

const RADII = [100, 200, 300, 500];

type Draft = {
  imageUri: string;
  name: string;
  address: string;
  category: string;
  note: string;
  endDate: string;
  coords: Coords | null;
  isPlace: boolean;
};

type Perms = { notif: boolean; fg: boolean; bg: boolean };

export default function App() {
  const [places, setPlaces] = useState<Place[]>([]);
  const [here, setHere] = useState<Coords | null>(null);
  const [perms, setPerms] = useState<Perms>({ notif: false, fg: false, bg: false });
  const [bgOk, setBgOk] = useState(false);
  const [watching, setWatching] = useState({ active: 0, total: 0 });
  const [radius, setRadius] = useState(200);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const placesRef = useRef<Place[]>([]);
  placesRef.current = places;

  const refreshPerms = useCallback(async () => {
    const [fg, bg] = await Promise.all([
      Location.getForegroundPermissionsAsync(),
      Location.getBackgroundPermissionsAsync(),
    ]);
    const notif = await setupNotifications().catch(() => false);
    setPerms({ notif, fg: fg.granted, bg: bg.granted });
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
      await refreshPerms();
    })();
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
      setBusy('지도에서 위치를 찾는 중…');
      const coords = ex.isPlace ? await geocodePlace(ex.name, ex.address) : null;
      setDraft({
        imageUri: saved.uri,
        name: ex.name,
        address: ex.address,
        category: ex.category,
        note: ex.note,
        endDate: ex.endDate ?? '',
        coords,
        isPlace: ex.isPlace,
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
    const coords = await geocodePlace(draft.name, draft.address);
    setDraft({ ...draft, coords });
    setBusy(null);
    if (!coords) Alert.alert('위치를 못 찾았어요', '주소를 더 구체적으로 고치거나 "현재 위치로"를 눌러 주세요.');
  };

  const useHere = async () => {
    if (!draft) return;
    const loc = here ?? (await Location.getCurrentPositionAsync({}).then((l) => l.coords).catch(() => null));
    if (loc) setDraft({ ...draft, coords: { latitude: loc.latitude, longitude: loc.longitude } });
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
      createdAt: Date.now(),
      lastNotifiedAt: null,
    };
    const next = await upsertPlace(place);
    setPlaces(next);
    setDraft(null);
    await resync();
  };

  const deletePlace = async (id: string) => {
    setPlaces(await removePlace(id));
  };

  const sorted = useMemo(() => {
    if (!here) return places;
    return [...places].sort((a, b) => distanceMeters(here, a) - distanceMeters(here, b));
  }, [places, here]);

  return (
    <SafeAreaView style={s.safe}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={s.body}>
        <Text style={s.title}>SnapSpot</Text>
        <Text style={s.subtitle}>찍어 둔 스크린샷 속 장소, 근처를 지나갈 때 알려 드려요</Text>

        <View style={s.statusRow}>
          <Chip ok={perms.notif} label="알림" />
          <Chip ok={perms.fg} label="위치" />
          <Chip ok={perms.bg && bgOk} label="앱 밖 알림" />
          <Text style={s.statusText}>
            감시 {watching.active}/{Math.min(watching.total, MAX_REGIONS) || places.length}곳
          </Text>
        </View>
        {!(perms.notif && perms.fg && perms.bg) && (
          <Button label="권한 설정하기" onPress={askPermissions} kind="ghost" />
        )}
        {!bgOk && (
          <Text style={s.warn}>
            지금은 앱 안에서만 알림이 와요. 앱이 꺼져 있을 때 알림은 설치용 빌드(APK)에서 동작해요.
          </Text>
        )}

        <Button label="📷 스크린샷에서 장소 추가" onPress={pickScreenshot} disabled={!!busy} />
        {busy && (
          <View style={s.busy}>
            <ActivityIndicator />
            <Text style={s.busyText}>{busy}</Text>
          </View>
        )}

        {draft && (
          <View style={s.card}>
            <Image source={{ uri: draft.imageUri }} style={s.preview} resizeMode="cover" />
            {!draft.isPlace && <Text style={s.warn}>장소가 잘 안 보이는 이미지예요. 직접 고쳐 주세요.</Text>}
            <Field label="장소 이름" value={draft.name} onChange={(v) => setDraft({ ...draft, name: v })} />
            <Field label="주소·위치 단서" value={draft.address} onChange={(v) => setDraft({ ...draft, address: v })} />
            <Field label="분류" value={draft.category} onChange={(v) => setDraft({ ...draft, category: v })} />
            <Field label="메모" value={draft.note} onChange={(v) => setDraft({ ...draft, note: v })} />
            <Field
              label="종료일 (YYYY-MM-DD, 팝업·전시)"
              value={draft.endDate}
              onChange={(v) => setDraft({ ...draft, endDate: v })}
            />
            <Text style={s.coords}>
              {draft.coords
                ? `위치 확인됨 · ${draft.coords.latitude.toFixed(5)}, ${draft.coords.longitude.toFixed(5)}${
                    here ? ` · 여기서 ${formatDistance(distanceMeters(here, draft.coords))}` : ''
                  }`
                : '위치를 아직 못 찾았어요'}
            </Text>
            <View style={s.row}>
              <Button label="주소로 다시 찾기" onPress={regeocode} kind="ghost" small />
              <Button label="현재 위치로" onPress={useHere} kind="ghost" small />
            </View>
            <Text style={s.label}>알림 반경</Text>
            <View style={s.row}>
              {RADII.map((r) => (
                <Pressable key={r} onPress={() => setRadius(r)} style={[s.radius, radius === r && s.radiusOn]}>
                  <Text style={[s.radiusText, radius === r && s.radiusTextOn]}>{r}m</Text>
                </Pressable>
              ))}
            </View>
            <View style={s.row}>
              <Button label="취소" onPress={() => setDraft(null)} kind="ghost" small />
              <Button label="저장" onPress={saveDraft} disabled={!draft.coords} small />
            </View>
          </View>
        )}

        <Text style={s.section}>저장한 장소 {places.length}곳</Text>
        {sorted.length === 0 && <Text style={s.empty}>아직 없어요. 맛집이나 팝업 스크린샷을 추가해 보세요.</Text>}
        {sorted.map((p) => {
          const d = here ? distanceMeters(here, p) : null;
          const reason = suppressReason(p);
          return (
            <View key={p.id} style={[s.card, d !== null && d <= p.radius && s.cardNear]}>
              <View style={s.rowBetween}>
                <Text style={s.placeName} numberOfLines={1}>{p.name}</Text>
                <Text style={s.badge}>{p.category}</Text>
              </View>
              {!!p.note && <Text style={s.note}>{p.note}</Text>}
              <Text style={s.meta} numberOfLines={1}>{p.address || '주소 없음'}</Text>
              <Text style={s.meta}>
                {d !== null ? `${formatDistance(d)} · ` : ''}반경 {p.radius}m
                {p.endDate ? ` · ${p.endDate}까지` : ''}
                {reason ? ` · ${reason}` : ''}
              </Text>
              <View style={s.row}>
                <Button label="알림 테스트" onPress={() => notifyIfAllowed(p.id, { force: true })} kind="ghost" small />
                <Button label="삭제" onPress={() => deletePlace(p.id)} kind="ghost" small />
              </View>
            </View>
          );
        })}
      </ScrollView>
    </SafeAreaView>
  );
}

function Chip({ ok, label }: { ok: boolean; label: string }) {
  return (
    <View style={[s.chip, ok ? s.chipOk : s.chipNo]}>
      <Text style={[s.chipText, ok ? s.chipTextOk : s.chipTextNo]}>{ok ? '✓' : '·'} {label}</Text>
    </View>
  );
}

function Button(props: { label: string; onPress: () => void; disabled?: boolean; kind?: 'solid' | 'ghost'; small?: boolean }) {
  const ghost = props.kind === 'ghost';
  return (
    <Pressable
      onPress={props.onPress}
      disabled={props.disabled}
      style={({ pressed }) => [
        s.btn,
        ghost ? s.btnGhost : s.btnSolid,
        props.small && s.btnSmall,
        (pressed || props.disabled) && { opacity: 0.6 },
      ]}
    >
      <Text style={[s.btnText, ghost && s.btnTextGhost]}>{props.label}</Text>
    </Pressable>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <View style={{ marginTop: 10 }}>
      <Text style={s.label}>{label}</Text>
      <TextInput value={value} onChangeText={onChange} style={s.input} placeholder="-" />
    </View>
  );
}

const C = { ink: '#1d1b22', sub: '#6b6780', line: '#e5e1ef', brand: '#4f437a', soft: '#f4f1fa' };

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#fff' },
  body: { padding: 20, paddingTop: 48, paddingBottom: 60 },
  title: { fontSize: 30, fontWeight: '800', color: C.ink },
  subtitle: { fontSize: 15, color: C.sub, marginTop: 4, marginBottom: 16 },
  statusRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, marginBottom: 10 },
  statusText: { color: C.sub, fontSize: 13, marginLeft: 4 },
  chip: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999 },
  chipOk: { backgroundColor: '#e3f3ec' },
  chipNo: { backgroundColor: '#f1eff4' },
  chipText: { fontSize: 13, fontWeight: '600' },
  chipTextOk: { color: '#0b5a41' },
  chipTextNo: { color: '#7a7590' },
  warn: { color: '#8a5a0a', backgroundColor: '#fbf0d9', padding: 10, borderRadius: 10, marginVertical: 8, fontSize: 13 },
  btn: { borderRadius: 12, paddingVertical: 14, paddingHorizontal: 16, alignItems: 'center', marginTop: 10 },
  btnSmall: { paddingVertical: 9, flex: 1 },
  btnSolid: { backgroundColor: C.brand },
  btnGhost: { backgroundColor: C.soft },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
  btnTextGhost: { color: C.brand },
  busy: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12 },
  busyText: { color: C.sub },
  card: { borderWidth: 1, borderColor: C.line, borderRadius: 16, padding: 14, marginTop: 12, backgroundColor: '#fff' },
  cardNear: { borderColor: C.brand, backgroundColor: C.soft },
  preview: { width: '100%', height: 180, borderRadius: 10, backgroundColor: C.soft },
  label: { fontSize: 12, color: C.sub, marginTop: 10, marginBottom: 4 },
  input: { borderWidth: 1, borderColor: C.line, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8, fontSize: 15, color: C.ink },
  coords: { marginTop: 12, fontSize: 13, color: C.brand },
  row: { flexDirection: 'row', gap: 8 },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  radius: { flex: 1, paddingVertical: 8, borderRadius: 10, backgroundColor: C.soft, alignItems: 'center' },
  radiusOn: { backgroundColor: C.brand },
  radiusText: { color: C.brand, fontWeight: '600' },
  radiusTextOn: { color: '#fff' },
  section: { fontSize: 18, fontWeight: '700', color: C.ink, marginTop: 28 },
  empty: { color: C.sub, marginTop: 8 },
  placeName: { fontSize: 17, fontWeight: '700', color: C.ink, flex: 1 },
  badge: { fontSize: 12, color: C.brand, backgroundColor: C.soft, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 8, overflow: 'hidden' },
  note: { marginTop: 4, color: C.ink },
  meta: { marginTop: 4, color: C.sub, fontSize: 13 },
});
