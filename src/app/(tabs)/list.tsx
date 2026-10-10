import { Linking, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { distanceMeters } from '../../geo';
import { suppressReason } from '../../notify';
import { usePlaces } from '../../state/places';
import { Notice, PlaceCard, WatchStatus } from '../../ui/components';
import { color, font, space, type } from '../../ui/theme';

export default function ListScreen() {
  const { places, here, perms, bgOk, alertsOn, watching, askPermissions, enableNetworkLocation, testNotify, deletePlace } =
    usePlaces();

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      <ScrollView contentContainerStyle={s.body}>
        <View style={s.header}>
          <Text style={type.hero} accessibilityRole="header">
            저장한 곳
          </Text>
          {places.length > 0 && (
            <Text style={type.meta}>
              {places.length}곳{here ? ', 가까운 순' : ''}
            </Text>
          )}
        </View>

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

        {places.length === 0 ? (
          <View style={s.empty}>
            <Text style={[type.body, { fontFamily: font.medium }]}>아직 저장한 곳이 없어요</Text>
            <Text style={type.meta}>
              아래 + 버튼으로 인스타 맛집이나 팝업 포스터 스크린샷을 추가하면, 장소를 찾아 저장하고 근처에 가면 알려
              드려요.
            </Text>
          </View>
        ) : (
          places.map((p) => (
            <PlaceCard
              key={p.id}
              place={p}
              distance={here ? distanceMeters(here, p) : null}
              status={suppressReason(p)}
              onTest={() => testNotify(p)}
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
