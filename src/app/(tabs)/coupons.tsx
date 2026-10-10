import { useRouter } from 'expo-router';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { BRANCH_RADIUS_M } from '../../background';
import { daysLeft, isActive } from '../../coupon';
import { usePlaces } from '../../state/places';
import { expiresLabel, nearestBranch } from '../../ui/couponView';
import { TicketCard } from '../../ui/components';
import { color, font, space, type } from '../../ui/theme';

export default function CouponsScreen() {
  const router = useRouter();
  const { coupons, win, here, watchSummary } = usePlaces();
  const active = coupons.filter((c) => isActive(c)).sort((a, b) => (a.expiresAt ?? '9999').localeCompare(b.expiresAt ?? '9999'));
  const past = coupons.filter((c) => !isActive(c));
  const branchSlots = Object.entries(watchSummary.byGroup)
    .filter(([g]) => g !== 'place')
    .reduce((n, [, v]) => n + v, 0);

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      <ScrollView contentContainerStyle={s.body}>
        <View style={s.header}>
          <Text style={type.hero} accessibilityRole="header">
            쿠폰함
          </Text>
          {active.length > 0 && (
            <Text style={type.meta}>
              {active.length}장{branchSlots ? `, 지점 ${branchSlots}곳 지켜보는 중` : ''}
            </Text>
          )}
        </View>

        {active.length === 0 ? (
          <View style={s.empty}>
            <Text style={[type.body, { fontFamily: font.medium }]}>프랜차이즈 쿠폰을 넣어 두세요</Text>
            <Text style={type.meta}>
              기프티콘이나 모바일 쿠폰 스크린샷을 아래 + 버튼으로 추가하면, 그 브랜드의 어느 지점이든 가까워졌을 때
              알려 드려요.
            </Text>
          </View>
        ) : (
          active.map((c) => {
            const nearest = nearestBranch(c, win, here);
            return (
              <TicketCard
                key={c.id}
                brand={c.brand}
                benefit={c.benefit}
                imageUri={c.imageUri}
                expiresLabel={expiresLabel(c)}
                days={daysLeft(c)}
                nearest={nearest}
                near={!!nearest && nearest.distance <= BRANCH_RADIUS_M}
                onPress={() => router.push(`/coupon/${c.id}`)}
              />
            );
          })
        )}

        {past.length > 0 && (
          <>
            <Text style={[type.title, { marginTop: space.l }]}>지난 쿠폰</Text>
            {past.map((c) => (
              <TicketCard
                key={c.id}
                brand={c.brand}
                benefit={c.benefit}
                imageUri={c.imageUri}
                expiresLabel={c.usedAt ? '사용했어요' : expiresLabel(c)}
                days={null}
                nearest={null}
                near={false}
                dimmed
                onPress={() => router.push(`/coupon/${c.id}`)}
              />
            ))}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: color.paper },
  body: { paddingHorizontal: space.l + 4, paddingTop: space.xl, paddingBottom: space.xxl, gap: space.l },
  header: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between' },
  empty: {
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.line,
    borderRadius: 16,
    padding: space.xl,
    gap: space.s,
  },
});
