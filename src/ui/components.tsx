import type { ReactNode } from 'react';
import { ActivityIndicator, Image, Pressable, StyleSheet, Text, TextInput, View, type ViewStyle } from 'react-native';
import { formatDistance } from '../geo';
import type { Candidate } from '../kakao';
import type { Place } from '../types';
import { color, font, space, type } from './theme';

type ButtonKind = 'primary' | 'secondary' | 'quiet';

export function Button(props: {
  label: string;
  onPress: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
  busy?: boolean;
  style?: ViewStyle;
}) {
  const kind = props.kind ?? 'primary';
  const off = props.disabled || props.busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!off, busy: !!props.busy }}
      onPress={props.onPress}
      disabled={off}
      hitSlop={kind === 'quiet' ? 8 : 0}
      style={({ pressed }) => [b.base, b[kind], pressed && b.pressed, off && b.off, props.style]}
    >
      {props.busy && <ActivityIndicator color={kind === 'primary' ? color.surface : color.green} />}
      <Text style={[b.text, b[`${kind}Text`]]}>{props.label}</Text>
    </Pressable>
  );
}

const b = StyleSheet.create({
  base: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.s, borderRadius: 12 },
  primary: { backgroundColor: color.green, minHeight: 52, paddingHorizontal: space.xl },
  secondary: { backgroundColor: color.greenSoft, minHeight: 44, paddingHorizontal: space.l },
  quiet: { minHeight: 36, paddingHorizontal: space.s },
  pressed: { opacity: 0.75 },
  off: { opacity: 0.5 },
  text: { fontFamily: font.bold, fontSize: 15 },
  primaryText: { color: color.surface, fontSize: 16 },
  secondaryText: { color: color.green },
  quietText: { color: color.muted, fontFamily: font.medium, fontSize: 14 },
});

export function Field(props: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  large?: boolean;
}) {
  return (
    <View style={{ gap: space.xs }}>
      <Text style={type.label}>{props.label}</Text>
      <TextInput
        accessibilityLabel={props.label}
        value={props.value}
        onChangeText={props.onChange}
        placeholder={props.placeholder}
        placeholderTextColor={color.muted}
        style={[f.input, props.large && f.large]}
      />
    </View>
  );
}

const f = StyleSheet.create({
  input: {
    ...type.body,
    borderWidth: 1,
    borderColor: color.line,
    borderRadius: 10,
    paddingHorizontal: space.m,
    paddingVertical: 10,
    backgroundColor: color.surface,
  },
  large: { fontFamily: font.bold, fontSize: 18, lineHeight: 24 },
});

/** Amber note with an optional fix-it action. Used for anything that stops alerts from arriving. */
export function Notice({ children, action }: { children: ReactNode; action?: { label: string; onPress: () => void } }) {
  return (
    <View style={n.box}>
      <Text style={n.text}>{children}</Text>
      {action && <Button label={action.label} onPress={action.onPress} kind="secondary" style={n.action} />}
    </View>
  );
}

const n = StyleSheet.create({
  box: { backgroundColor: color.amberSoft, borderRadius: 12, padding: space.l, gap: space.m },
  text: { ...type.body, fontSize: 14, color: color.amber },
  action: { alignSelf: 'flex-start', backgroundColor: color.surface },
});

/** One line that says whether alerts will arrive with the app closed. */
export function WatchStatus({ on, watching }: { on: boolean; watching: number }) {
  return (
    <View style={w.row} accessibilityRole="text">
      <View style={[w.dot, { backgroundColor: on ? color.green : color.line }]} />
      <Text style={[type.body, { fontFamily: font.medium }]}>
        {on ? '앱을 닫아도 근처에서 알려 드려요' : '앱을 열어 둔 동안만 알려 드려요'}
      </Text>
      {on && watching > 0 && <Text style={type.meta}>{watching}곳 지켜보는 중</Text>}
    </View>
  );
}

const w = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', columnGap: space.s, rowGap: 2 },
  dot: { width: 8, height: 8, borderRadius: 4 },
});

/** The screenshot the place came from. It is how people recognize their own saves. */
export function Shot({ uri, width, height }: { uri: string | null; width: number; height: number }) {
  return (
    <View style={[sh.frame, { width, height }]}>
      {uri ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" accessibilityIgnoresInvertColors />
      ) : (
        <Text style={sh.empty}>📍</Text>
      )}
    </View>
  );
}

const sh = StyleSheet.create({
  frame: {
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: color.greenSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  empty: { fontSize: 22 },
});

function endLabel(endDate: string | null): string | null {
  if (!endDate) return null;
  const [, m, d] = endDate.split('-');
  return `${Number(m)}월 ${Number(d)}일까지`;
}

export function PlaceCard(props: {
  place: Place;
  distance: number | null;
  status: string | null;
  onTest: () => void;
  onMap?: () => void;
  onDelete: () => void;
}) {
  const { place: p, distance } = props;
  const near = distance !== null && distance <= p.radius;
  const facts = [`반경 ${p.radius}m`, endLabel(p.endDate), props.status].filter(Boolean) as string[];
  return (
    <View style={[c.card, near && c.cardNear]}>
      {near && (
        <View style={c.nearBand}>
          <Text style={c.nearText}>지금 근처예요</Text>
        </View>
      )}
      <View style={c.top}>
        <View style={c.shotCol}>
          <Shot uri={p.imageUri} width={72} height={96} />
          {distance !== null && !near && (
            <Text style={c.distance} accessibilityLabel={`여기서 ${formatDistance(distance)}`}>
              {formatDistance(distance)}
            </Text>
          )}
        </View>
        <View style={c.info}>
          <Text style={type.title} numberOfLines={2}>
            {p.name}
          </Text>
          <Text style={c.category}>{p.category}</Text>
          {!!p.note && (
            <Text style={[type.body, { fontSize: 14 }]} numberOfLines={2}>
              {p.note}
            </Text>
          )}
          <Text style={type.meta} numberOfLines={1}>
            {p.address || '주소 없음'}
          </Text>
        </View>
      </View>
      <View style={c.bottom}>
        <View style={c.facts}>
          {facts.map((t) => (
            <Text key={t} style={c.fact}>
              {t}
            </Text>
          ))}
        </View>
        <View style={c.actions}>
          <Button label="알림 테스트" onPress={props.onTest} kind="quiet" />
          {props.onMap && <Button label="지도" onPress={props.onMap} kind="quiet" />}
          <Button label="삭제" onPress={props.onDelete} kind="quiet" />
        </View>
      </View>
    </View>
  );
}

const c = StyleSheet.create({
  card: {
    backgroundColor: color.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: color.line,
    padding: space.l,
    gap: space.m,
    overflow: 'hidden',
  },
  cardNear: { borderColor: color.ink, borderWidth: 1.5 },
  nearBand: {
    backgroundColor: color.highlight,
    marginHorizontal: -space.l,
    marginTop: -space.l,
    paddingHorizontal: space.l,
    paddingVertical: space.s,
  },
  nearText: { fontFamily: font.display, fontSize: 16, color: color.ink },
  top: { flexDirection: 'row', gap: space.m },
  shotCol: { width: 72, alignItems: 'center', gap: space.xs },
  distance: { ...type.distance, fontSize: 17, lineHeight: 22 },
  info: { flex: 1, gap: 2 },
  category: { fontFamily: font.medium, fontSize: 13, lineHeight: 18, color: color.green },
  bottom: { gap: space.xs },
  facts: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  fact: {
    ...type.meta,
    fontSize: 12,
    backgroundColor: color.paper,
    borderRadius: 6,
    paddingHorizontal: space.s,
    paddingVertical: 2,
    overflow: 'hidden',
  },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', marginRight: -space.s },
});

/** Where the pin came from and how sure we are. */
export function MatchBox({ matched, hasCoords }: { matched: Candidate | null; hasCoords: boolean }) {
  if (!hasCoords) {
    return (
      <View style={[m.box, m.warn]}>
        <Text style={[m.head, { color: color.amber }]}>위치를 못 찾았어요</Text>
        <Text style={[type.meta, { color: color.amber }]}>주소를 고쳐서 다시 찾거나, 지금 위치로 저장하세요.</Text>
      </View>
    );
  }
  if (!matched) {
    return (
      <View style={m.box}>
        <Text style={m.head}>휴대폰 지도에서 위치를 찾았어요</Text>
        <Text style={type.meta}>가게 이름은 확인하지 못했어요.</Text>
      </View>
    );
  }
  const sure = matched.score >= 0.7;
  const pct = Math.round(matched.score * 100);
  return (
    <View style={[m.box, !sure && m.warn]}>
      <View style={m.row}>
        <Text style={[m.head, !sure && { color: color.amber }]} numberOfLines={1}>
          {matched.source === 'address' ? '주소 위치' : matched.name}
        </Text>
        <Text style={[m.pct, !sure && { color: color.amber }]}>{pct}%</Text>
      </View>
      <Text style={type.meta} numberOfLines={1}>
        {matched.address}
      </Text>
      <Text style={[type.meta, { color: sure ? color.green : color.amber }]}>
        {sure ? '카카오맵에서 찾은 장소예요' : '비슷한 곳을 찾았어요. 맞는지 확인해 주세요'}
      </Text>
    </View>
  );
}

const m = StyleSheet.create({
  box: { backgroundColor: color.greenSoft, borderRadius: 12, padding: space.m, gap: 2 },
  warn: { backgroundColor: color.amberSoft },
  row: { flexDirection: 'row', alignItems: 'baseline', gap: space.s },
  head: { fontFamily: font.bold, fontSize: 16, lineHeight: 22, color: color.ink, flex: 1 },
  pct: { fontFamily: font.display, fontSize: 20, color: color.green },
});

export function CandidateRow({ c, onPress }: { c: Candidate; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${c.name}, 일치도 ${Math.round(c.score * 100)}퍼센트로 바꾸기`}
      onPress={onPress}
      style={({ pressed }) => [r.row, pressed && { backgroundColor: color.paper }]}
    >
      <View style={{ flex: 1 }}>
        <Text style={[type.body, { fontFamily: font.medium }]} numberOfLines={1}>
          {c.source === 'address' ? '주소 위치' : c.name}
        </Text>
        <Text style={type.meta} numberOfLines={1}>
          {c.address}
        </Text>
      </View>
      <Text style={r.pct}>{Math.round(c.score * 100)}%</Text>
    </Pressable>
  );
}

const r = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m,
    paddingVertical: 10,
    paddingHorizontal: space.m,
    borderTopWidth: 1,
    borderColor: color.line,
  },
  pct: { fontFamily: font.medium, fontSize: 14, color: color.muted },
});

export function Segmented<T extends string | number>(props: {
  options: readonly T[];
  value: T;
  onChange: (v: T) => void;
  format: (v: T) => string;
}) {
  return (
    <View style={sg.track} accessibilityRole="radiogroup">
      {props.options.map((o) => {
        const on = o === props.value;
        return (
          <Pressable
            key={o}
            accessibilityRole="radio"
            accessibilityState={{ selected: on }}
            onPress={() => props.onChange(o)}
            style={[sg.item, on && sg.itemOn]}
          >
            <Text style={[sg.text, on && sg.textOn]}>{props.format(o)}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const sg = StyleSheet.create({
  track: { flexDirection: 'row', backgroundColor: color.paper, borderRadius: 12, padding: 4, gap: 4 },
  item: { flex: 1, minHeight: 40, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  itemOn: { backgroundColor: color.surface, borderWidth: 1, borderColor: color.line },
  text: { fontFamily: font.medium, fontSize: 14, color: color.muted },
  textOn: { color: color.ink, fontFamily: font.bold },
});

/** "D-21", "오늘까지", or null without an expiry date. */
export function dDay(days: number | null): string | null {
  if (days === null) return null;
  if (days < 0) return '기간 지남';
  return days === 0 ? '오늘까지' : `D-${days}`;
}

/**
 * A coupon as a ticket: perforation between what it is and where to use it.
 * The yellow band means the same as on place cards: a branch is near right now.
 */
export function TicketCard(props: {
  brand: string;
  benefit: string;
  imageUri: string | null;
  expiresLabel: string | null;
  days: number | null;
  nearest: { name: string; distance: number } | null;
  near: boolean;
  dimmed?: boolean;
  onPress: () => void;
}) {
  const urgent = props.days !== null && props.days <= 3 && !props.dimmed;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${props.brand} ${props.benefit} 쿠폰 열기`}
      onPress={props.onPress}
      style={({ pressed }) => [tk.card, props.near && tk.cardNear, props.dimmed && tk.dimmed, pressed && { opacity: 0.85 }]}
    >
      {props.near && props.nearest && (
        <View style={tk.nearBand}>
          <Text style={tk.nearText}>지금 근처: {props.nearest.name}</Text>
        </View>
      )}
      <View style={tk.top}>
        <Shot uri={props.imageUri} width={60} height={80} />
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={tk.brand} numberOfLines={1}>
            {props.brand}
          </Text>
          <Text style={[type.body, { fontFamily: font.medium }]} numberOfLines={2}>
            {props.benefit}
          </Text>
          {props.expiresLabel && <Text style={type.meta}>{props.expiresLabel}</Text>}
        </View>
        {dDay(props.days) && <Text style={[tk.dday, urgent && tk.ddayUrgent]}>{dDay(props.days)}</Text>}
      </View>
      <View style={tk.perforation}>
        <View style={[tk.notch, { left: -9 }]} />
        <View style={tk.dash} />
        <View style={[tk.notch, { right: -9 }]} />
      </View>
      <Text style={type.meta} numberOfLines={1}>
        {props.nearest
          ? `가장 가까운 지점  ${props.nearest.name}  ${formatDistance(props.nearest.distance)}`
          : props.dimmed
            ? '알림이 꺼진 쿠폰이에요'
            : '근처 지점을 찾는 중이에요'}
      </Text>
    </Pressable>
  );
}

const tk = StyleSheet.create({
  card: {
    backgroundColor: color.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: color.line,
    padding: space.l,
    gap: space.m,
    overflow: 'hidden',
  },
  cardNear: { borderColor: color.ink, borderWidth: 1.5 },
  dimmed: { opacity: 0.55 },
  nearBand: {
    backgroundColor: color.highlight,
    marginHorizontal: -space.l,
    marginTop: -space.l,
    paddingHorizontal: space.l,
    paddingVertical: space.s,
  },
  nearText: { fontFamily: font.display, fontSize: 15, color: color.ink },
  top: { flexDirection: 'row', gap: space.m, alignItems: 'flex-start' },
  brand: { fontFamily: font.display, fontSize: 20, lineHeight: 26, color: color.ink },
  dday: { fontFamily: font.display, fontSize: 18, color: color.green },
  ddayUrgent: { color: color.amber },
  perforation: { height: 18, justifyContent: 'center', marginHorizontal: -space.l },
  dash: { borderTopWidth: 1.5, borderColor: color.line, borderStyle: 'dashed', marginHorizontal: space.m },
  notch: {
    position: 'absolute',
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: color.paper,
    borderWidth: 1,
    borderColor: color.line,
  },
});

/** How the OS geofence slots are shared right now: one segment per group. */
export function SlotMeter(props: { total: number; parts: { label: string; count: number; tone: string }[] }) {
  const used = props.parts.reduce((n, p) => n + p.count, 0);
  return (
    <View style={{ gap: space.m }}>
      <View style={sm.track} accessibilityLabel={`${props.total}칸 중 ${used}칸 사용`}>
        {props.parts
          .filter((p) => p.count > 0)
          .map((p) => (
            <View key={p.label} style={{ flex: p.count, backgroundColor: p.tone }} />
          ))}
        <View style={{ flex: Math.max(props.total - used, 0), backgroundColor: color.paper }} />
      </View>
      {props.parts.map((p) => (
        <View key={p.label} style={sm.row}>
          <View style={[sm.swatch, { backgroundColor: p.tone }]} />
          <Text style={[type.body, { flex: 1 }]}>{p.label}</Text>
          <Text style={sm.count}>{p.count}칸</Text>
        </View>
      ))}
      <View style={sm.row}>
        <View style={[sm.swatch, { backgroundColor: color.paper, borderWidth: 1, borderColor: color.line }]} />
        <Text style={[type.body, { flex: 1, color: color.muted }]}>비어 있음</Text>
        <Text style={[sm.count, { color: color.muted }]}>{Math.max(props.total - used, 0)}칸</Text>
      </View>
    </View>
  );
}

const sm = StyleSheet.create({
  track: { flexDirection: 'row', height: 14, borderRadius: 7, overflow: 'hidden', gap: 2, backgroundColor: color.surface },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.s },
  swatch: { width: 12, height: 12, borderRadius: 3 },
  count: { fontFamily: font.display, fontSize: 16, color: color.ink },
});
