import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MAX_REGIONS, REFRESH_RADIUS_M } from '../../background';
import { brandKey } from '../../brand';
import { usePlaces } from '../../state/places';
import { Notice, SlotMeter, WatchStatus } from '../../ui/components';
import { color, font, space, type } from '../../ui/theme';

/** Brand segments are told apart by shades of ink; saved places keep the action green. */
const BRAND_TONES = ['#1F2B2A', '#4E6B63', '#8AA39B', '#B9C9C2', '#6C7774'];

function ago(at: number): string {
  const min = Math.round((Date.now() - at) / 60000);
  if (min < 1) return '방금';
  if (min < 60) return `${min}분 전`;
  return `${Math.round(min / 60)}시간 전`;
}

export default function StatusScreen() {
  const { perms, bgOk, alertsOn, watching, win, coupons, askPermissions, enableNetworkLocation } = usePlaces();
  const brandNames = new Map(coupons.map((c) => [brandKey(c.brand), c.brand]));
  const groups = Object.entries(win?.byGroup ?? {});
  const parts = [
    { label: '저장한 장소', count: win?.byGroup.place ?? 0, tone: color.green },
    ...groups
      .filter(([g]) => g !== 'place')
      .map(([g, n], i) => ({ label: `${brandNames.get(g) ?? g} 지점`, count: n, tone: BRAND_TONES[i % BRAND_TONES.length] })),
    ...(win?.here ? [{ label: `갱신 구역 (반경 ${REFRESH_RADIUS_M / 1000}km)`, count: 1, tone: color.highlight }] : []),
  ];

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      <ScrollView contentContainerStyle={s.body}>
        <Text style={type.hero} accessibilityRole="header">
          알림 현황
        </Text>
        <WatchStatus on={alertsOn} watching={watching} />

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

        <View style={s.card}>
          <Text style={type.title}>지켜보는 곳</Text>
          <Text style={type.meta}>
            휴대폰은 한 번에 {MAX_REGIONS}곳까지만 지켜볼 수 있어요. 가까운 곳부터 장소와 브랜드별로 고르게 나눠 담고,
            지금 위치에서 {REFRESH_RADIUS_M / 1000}km를 벗어나면 그 자리에서 다시 골라요.
          </Text>
          <SlotMeter total={MAX_REGIONS} parts={parts} />
        </View>

        <View style={s.card}>
          <Text style={type.title}>마지막으로 다시 고른 때</Text>
          {win ? (
            <View style={{ gap: space.xs }}>
              <View style={s.fact}>
                <Text style={type.body}>시각</Text>
                <Text style={s.value}>{ago(win.at)}</Text>
              </View>
              <View style={s.fact}>
                <Text style={type.body}>카카오 지점 검색</Text>
                <Text style={s.value}>{win.requests === 0 ? '저장해 둔 결과 사용' : `${win.requests}회`}</Text>
              </View>
              <View style={s.fact}>
                <Text style={type.body}>기준 위치</Text>
                <Text style={s.value}>{win.here ? '확인됨' : '아직 모름'}</Text>
              </View>
            </View>
          ) : (
            <Text style={type.meta}>아직 고른 적이 없어요. 위치 권한을 허용하면 바로 시작해요.</Text>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: color.paper },
  body: { paddingHorizontal: space.l + 4, paddingTop: space.xl, paddingBottom: space.xxl, gap: space.l },
  card: { backgroundColor: color.surface, borderRadius: 16, borderWidth: 1, borderColor: color.line, padding: space.l, gap: space.m },
  fact: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  value: { fontFamily: font.bold, fontSize: 15, color: color.ink },
});
