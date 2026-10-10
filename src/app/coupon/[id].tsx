import { Feather } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { BRANCH_RADIUS_M } from '../../background';
import { brandKey } from '../../brand';
import { daysLeft, isActive } from '../../coupon';
import { distanceMeters, formatDistance } from '../../geo';
import { usePlaces } from '../../state/places';
import { Button, dDay } from '../../ui/components';
import { expiresLabel } from '../../ui/couponView';
import { color, font, space, type } from '../../ui/theme';

/** One coupon, sized for the counter: the screenshot (with its barcode) as large as the screen allows. */
export default function CouponScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const { coupons, win, here, markCouponUsed, deleteCoupon, testCoupon } = usePlaces();
  const c = coupons.find((x) => x.id === id);

  if (!c) {
    return (
      <SafeAreaView style={s.safe}>
        <View style={s.missing}>
          <Text style={type.title}>쿠폰을 찾지 못했어요</Text>
          <Text style={type.meta}>삭제했거나 다른 기기에서 추가한 쿠폰일 수 있어요.</Text>
          <Button label="쿠폰함으로" onPress={() => router.replace('/coupons')} kind="secondary" />
        </View>
      </SafeAreaView>
    );
  }

  const active = isActive(c);
  const key = brandKey(c.brand);
  const branches = here
    ? Object.values(win?.branches ?? {})
        .filter((b) => b.brandKey === key)
        .map((b) => ({ ...b, distance: distanceMeters(here, b) }))
        .sort((a, b) => a.distance - b.distance)
        .slice(0, 3)
    : [];

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right', 'bottom']}>
      <View style={s.head}>
        <View style={{ flex: 1 }}>
          <Text style={s.brand}>{c.brand}</Text>
          <Text style={[type.body, { fontFamily: font.medium }]}>{c.benefit}</Text>
        </View>
        <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={() => router.back()} hitSlop={12} style={s.close}>
          <Feather name="x" size={22} color={color.ink} />
        </Pressable>
      </View>

      <ScrollView contentContainerStyle={s.body}>
        <View style={s.imageWrap}>
          {c.imageUri ? (
            <Image source={{ uri: c.imageUri }} style={s.image} resizeMode="contain" accessibilityLabel={`${c.brand} 쿠폰 이미지`} />
          ) : (
            <Text style={type.meta}>쿠폰 이미지가 없어요</Text>
          )}
        </View>

        <View style={s.facts}>
          <Text style={type.body}>{c.usedAt ? '사용한 쿠폰이에요' : (expiresLabel(c) ?? '유효기간 없음')}</Text>
          {active && dDay(daysLeft(c)) && <Text style={s.dday}>{dDay(daysLeft(c))}</Text>}
        </View>

        {active && branches.length > 0 && (
          <View style={s.branches}>
            <Text style={[type.label, s.branchesHead]}>가까운 지점</Text>
            {branches.map((b) => (
              <View key={b.name} style={[s.branch, b.distance <= BRANCH_RADIUS_M && s.branchNear]}>
                <Text style={[type.body, { flex: 1 }]} numberOfLines={1}>
                  {b.name}
                </Text>
                <Text style={s.distance}>{formatDistance(b.distance)}</Text>
              </View>
            ))}
          </View>
        )}
      </ScrollView>

      <View style={s.footer}>
        {active ? (
          <>
            <Button label="삭제" onPress={() => deleteCoupon(c, () => router.back())} kind="quiet" />
            <Button label="알림 테스트" onPress={() => testCoupon(c)} kind="secondary" style={{ flex: 1 }} />
            <Button
              label="사용했어요"
              onPress={async () => {
                await markCouponUsed(c);
                router.back();
              }}
              style={{ flex: 1.4 }}
            />
          </>
        ) : (
          <Button
            label="쿠폰 삭제"
            onPress={() => deleteCoupon(c, () => router.back())}
            kind="secondary"
            style={{ flex: 1 }}
          />
        )}
      </View>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: color.paper },
  head: { flexDirection: 'row', alignItems: 'flex-start', paddingHorizontal: space.l + 4, paddingVertical: space.m, gap: space.m },
  brand: { fontFamily: font.display, fontSize: 28, lineHeight: 34, color: color.ink },
  close: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: color.surface },
  body: { paddingHorizontal: space.l + 4, paddingBottom: space.xl, gap: space.l },
  imageWrap: {
    backgroundColor: color.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: color.line,
    padding: space.m,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 200,
  },
  image: { width: '100%', aspectRatio: 9 / 16 },
  facts: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  dday: { fontFamily: font.display, fontSize: 22, color: color.green },
  branches: { borderWidth: 1, borderColor: color.line, borderRadius: 12, overflow: 'hidden', backgroundColor: color.surface },
  branchesHead: { paddingHorizontal: space.m, paddingVertical: space.s },
  branch: { flexDirection: 'row', alignItems: 'center', gap: space.m, paddingHorizontal: space.m, paddingVertical: 10, borderTopWidth: 1, borderColor: color.line },
  branchNear: { backgroundColor: color.highlight },
  distance: { fontFamily: font.display, fontSize: 16, color: color.ink },
  footer: { flexDirection: 'row', gap: space.s, paddingHorizontal: space.l + 4, paddingVertical: space.m, borderTopWidth: 1, borderTopColor: color.line },
  missing: { flex: 1, justifyContent: 'center', padding: space.xl, gap: space.m },
});
