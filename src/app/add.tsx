import { Feather } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo,
  Animated,
  Easing,
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { distanceMeters, formatDistance } from '../geo';
import { RADII, usePlaces, type Draft } from '../state/places';
import { Button, CandidateRow, Field, MatchBox, Notice, Segmented, Shot } from '../ui/components';
import { color, font, space, type } from '../ui/theme';

const KINDS = ['place', 'coupon'] as const;

export default function AddScreen() {
  const router = useRouter();
  const { draft, updateDraft, cancelDraft, saveDraft, setDraftKind } = usePlaces();

  // Leaving by the back gesture drops the draft.
  useEffect(() => cancelDraft, []); // eslint-disable-line react-hooks/exhaustive-deps

  const close = () => {
    cancelDraft();
    if (router.canGoBack()) router.back();
  };
  const save = async () => {
    const saved = await saveDraft();
    if (!saved) return;
    // One action, not back() + navigate(): two in a row can land out of order and pop a tab instead.
    // Coupons live in their own tab; places show up on the map, which selects the new pin.
    router.dismissTo(saved.kind === 'coupon' ? '/coupons' : '/');
  };

  if (!draft) return <View style={s.safe} />;
  const isCoupon = draft.kind === 'coupon';
  const ready = draft.stage === 'ready';

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right', 'bottom']}>
      <View style={s.head}>
        <Text style={[type.title, { flex: 1 }]}>
          {!ready ? '스크린샷 읽는 중' : isCoupon ? '새 쿠폰 확인' : '새 장소 확인'}
        </Text>
        <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={close} hitSlop={12} style={s.close}>
          <Feather name="x" size={22} color={color.ink} />
        </Pressable>
      </View>

      {!ready ? (
        <Reading draft={draft} />
      ) : (
        <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          <ScrollView contentContainerStyle={s.body} keyboardShouldPersistTaps="handled">
            <Segmented
              options={KINDS}
              value={draft.kind}
              onChange={setDraftKind}
              format={(k) => (k === 'place' ? '가 볼 장소' : '프랜차이즈 쿠폰')}
            />
            {isCoupon ? <CouponForm /> : <PlaceForm />}
          </ScrollView>
          <View style={s.footer}>
            <Button
              label={isCoupon ? '쿠폰함에 저장' : '지도에 저장'}
              onPress={save}
              disabled={isCoupon ? !draft.brand.trim() : !draft.coords}
            />
          </View>
        </KeyboardAvoidingView>
      )}
    </SafeAreaView>
  );
}

function PlaceForm() {
  const { draft, here, busy, updateDraft, regeocode, pickCandidate, pinToHere } = usePlaces();
  if (!draft) return null;
  return (
    <>
      {!draft.isPlace && <Notice>이 이미지에서는 장소를 찾기 어려웠어요. 이름과 주소를 직접 고쳐 주세요.</Notice>}

      <View style={s.top}>
        <Shot uri={draft.imageUri} width={96} height={128} />
        <View style={{ flex: 1, gap: space.m }}>
          <Field label="장소 이름" value={draft.name} onChange={(v) => updateDraft({ name: v })} large />
          <Field label="분류" value={draft.category} onChange={(v) => updateDraft({ category: v })} />
        </View>
      </View>
      <Field label="메모" value={draft.note} onChange={(v) => updateDraft({ note: v })} />
      <Field label="주소나 위치 단서" value={draft.address} onChange={(v) => updateDraft({ address: v })} />
      <Field
        label="종료일 (팝업·전시만)"
        value={draft.endDate}
        onChange={(v) => updateDraft({ endDate: v })}
        placeholder="예: 2026-10-31 (없으면 비워 두세요)"
      />

      <View style={{ gap: space.s }}>
        <Text style={type.label}>지도에서 찾은 위치</Text>
        <MatchBox matched={draft.matched} hasCoords={!!draft.coords} />
        {draft.coords && here && (
          <Text style={type.meta}>여기서 {formatDistance(distanceMeters(here, draft.coords))}</Text>
        )}
        {draft.candidates.filter((c) => c !== draft.matched).length > 0 && (
          <View style={s.candidates}>
            <Text style={[type.label, s.candidatesHead]}>다른 후보</Text>
            {draft.candidates
              .filter((c) => c !== draft.matched)
              .slice(0, 3)
              .map((c) => (
                <CandidateRow key={`${c.source}-${c.kakaoId ?? c.address}`} c={c} onPress={() => pickCandidate(c)} />
              ))}
          </View>
        )}
        <View style={s.row}>
          <Button label="주소로 다시 찾기" onPress={regeocode} kind="secondary" busy={busy} style={{ flex: 1 }} />
          <Button label="지금 위치로" onPress={pinToHere} kind="secondary" style={{ flex: 1 }} />
        </View>
      </View>

      <View style={{ gap: space.s }}>
        <Text style={type.label}>이만큼 가까워지면 알려 드려요</Text>
        <Segmented options={RADII} value={draft.radius} onChange={(r) => updateDraft({ radius: r })} format={(r) => `${r}m`} />
      </View>
    </>
  );
}

/** Coupon fields plus a preview of the branches that will trigger alerts. */
function CouponForm() {
  const { draft, here, busy, updateDraft, refreshBranchPreview } = usePlaces();
  if (!draft) return null;
  const branches = draft.branches;
  return (
    <>
      <View style={s.top}>
        <Shot uri={draft.imageUri} width={96} height={128} />
        <View style={{ flex: 1, gap: space.m }}>
          <Field label="브랜드" value={draft.brand} onChange={(v) => updateDraft({ brand: v })} large />
          <Field label="받을 수 있는 것" value={draft.benefit} onChange={(v) => updateDraft({ benefit: v })} />
        </View>
      </View>
      <Field
        label="유효기간 마지막 날"
        value={draft.endDate}
        onChange={(v) => updateDraft({ endDate: v })}
        placeholder="예: 2026-12-31 (없으면 비워 두세요)"
      />

      <View style={{ gap: space.s }}>
        <Text style={type.label}>알림이 울릴 지점</Text>
        {!here ? (
          <View style={[s.preview, s.previewWarn]}>
            <Text style={[s.previewHead, { color: color.amber }]}>위치를 아직 몰라요</Text>
            <Text style={[type.meta, { color: color.amber }]}>
              위치를 허용하면 근처 지점을 미리 보여 드려요. 저장은 지금 해도 돼요.
            </Text>
          </View>
        ) : branches === null ? (
          <View style={s.preview}>
            <Text style={s.previewHead}>근처 지점을 찾는 중이에요</Text>
          </View>
        ) : branches.length === 0 ? (
          <View style={[s.preview, s.previewWarn]}>
            <Text style={[s.previewHead, { color: color.amber }]}>
              근처에서 {draft.brand || '이 브랜드'} 지점을 못 찾았어요
            </Text>
            <Text style={[type.meta, { color: color.amber }]}>
              브랜드 이름을 고친 뒤 다시 찾아 보세요. 멀리 가면 그곳 지점을 다시 찾아요.
            </Text>
          </View>
        ) : (
          <View style={s.preview}>
            <View style={s.previewRow}>
              <Text style={[s.previewHead, { flex: 1 }]}>
                근처 {draft.brand} {branches.length}곳
              </Text>
              <Text style={s.previewCount}>{formatDistance(distanceMeters(here, branches[0]))}</Text>
            </View>
            <Text style={type.meta}>가장 가까운 곳은 {branches[0].name}이에요. 이 중 어디든 가까워지면 알려 드려요.</Text>
          </View>
        )}
        {here && branches && branches.length > 0 && (
          <View style={s.candidates}>
            {branches.slice(0, 3).map((b) => (
              <View key={b.kakaoId} style={s.branchRow}>
                <Text style={[type.body, { flex: 1 }]} numberOfLines={1}>
                  {b.name}
                </Text>
                <Text style={type.meta}>{formatDistance(distanceMeters(here, b))}</Text>
              </View>
            ))}
          </View>
        )}
        <Button label="브랜드로 다시 찾기" onPress={refreshBranchPreview} kind="secondary" busy={busy} />
      </View>
    </>
  );
}

/** The screenshot, with a highlighter sweeping over it while it is being read. */
function Reading({ draft }: { draft: Draft }) {
  const [frame, setFrame] = useState({ w: 0, h: 0 });
  const sweep = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    let loop: Animated.CompositeAnimation | null = null;
    AccessibilityInfo.isReduceMotionEnabled().then((reduce) => {
      if (reduce) return;
      loop = Animated.loop(
        Animated.timing(sweep, { toValue: 1, duration: 1400, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      );
      loop.start();
    });
    return () => loop?.stop();
  }, [sweep]);

  const steps: { label: string; state: 'done' | 'now' | 'todo' }[] = [
    { label: '스크린샷에서 장소 정보 읽기', state: draft.stage === 'reading' ? 'now' : 'done' },
    {
      label: draft.kind === 'coupon' ? '근처 지점 찾기' : '카카오맵에서 장소 찾기',
      state: draft.stage === 'locating' ? 'now' : 'todo',
    },
  ];

  return (
    <View style={s.reading}>
      <View
        style={s.readingFrame}
        onLayout={(e) => setFrame({ w: e.nativeEvent.layout.width, h: e.nativeEvent.layout.height })}
      >
        <Image source={{ uri: draft.imageUri }} style={StyleSheet.absoluteFill} resizeMode="cover" />
        {frame.h > 0 && (
          <Animated.View
            style={[
              s.highlighter,
              { transform: [{ translateY: sweep.interpolate({ inputRange: [0, 1], outputRange: [-40, frame.h] }) }] },
            ]}
          />
        )}
      </View>
      <View style={s.steps} accessibilityLiveRegion="polite">
        {steps.map((st) => (
          <View key={st.label} style={s.step}>
            <View style={[s.stepDot, st.state === 'done' && s.stepDone, st.state === 'now' && s.stepNow]}>
              {st.state === 'done' && <Feather name="check" size={12} color={color.surface} />}
            </View>
            <Text style={[type.body, st.state === 'todo' && { color: color.muted }, st.state === 'now' && { fontFamily: font.bold }]}>
              {st.label}
            </Text>
          </View>
        ))}
        {draft.name ? <Text style={type.meta}>찾은 이름: {draft.name}</Text> : null}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: color.paper },
  head: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space.l + 4, paddingVertical: space.m },
  close: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: color.surface },
  body: { paddingHorizontal: space.l + 4, paddingBottom: space.xl, gap: space.l + 4 },
  top: { flexDirection: 'row', gap: space.m, alignItems: 'flex-start' },
  candidates: { borderWidth: 1, borderColor: color.line, borderRadius: 12, overflow: 'hidden', backgroundColor: color.surface },
  candidatesHead: { paddingHorizontal: space.m, paddingVertical: space.s },
  preview: { backgroundColor: color.greenSoft, borderRadius: 12, padding: space.m, gap: 2 },
  previewWarn: { backgroundColor: color.amberSoft },
  previewRow: { flexDirection: 'row', alignItems: 'baseline', gap: space.s },
  previewHead: { fontFamily: font.bold, fontSize: 16, lineHeight: 22, color: color.ink },
  previewCount: { fontFamily: font.display, fontSize: 20, color: color.green },
  branchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m,
    paddingHorizontal: space.m,
    paddingVertical: 10,
    borderTopWidth: 1,
    borderColor: color.line,
  },
  row: { flexDirection: 'row', gap: space.s },
  footer: { paddingHorizontal: space.l + 4, paddingVertical: space.m, borderTopWidth: 1, borderTopColor: color.line, backgroundColor: color.paper },
  reading: { flex: 1, alignItems: 'center', paddingHorizontal: space.xl, paddingTop: space.l, gap: space.xl },
  readingFrame: {
    width: '62%',
    aspectRatio: 9 / 16,
    borderRadius: 18,
    overflow: 'hidden',
    backgroundColor: color.greenSoft,
    borderWidth: 4,
    borderColor: color.surface,
  },
  highlighter: { position: 'absolute', left: 0, right: 0, height: 40, backgroundColor: color.highlight, opacity: 0.55 },
  steps: { alignSelf: 'stretch', gap: space.m },
  step: { flexDirection: 'row', alignItems: 'center', gap: space.m },
  stepDot: { width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: color.line, alignItems: 'center', justifyContent: 'center' },
  stepNow: { borderColor: color.green },
  stepDone: { backgroundColor: color.green, borderColor: color.green },
});
