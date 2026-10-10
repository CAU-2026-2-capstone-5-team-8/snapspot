import { useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { distanceMeters, formatDistance } from '../../geo';
import { SpotMap, type SpotMapHandle } from '../../map/SpotMap';
import { usePlaces } from '../../state/places';
import type { Place } from '../../types';
import { Shot } from '../../ui/components';
import { color, font, space, type } from '../../ui/theme';

const GAP = space.m;

export default function MapScreen() {
  const { places, here, alertsOn, watching, justSavedId, clearJustSaved } = usePlaces();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const cardW = width - space.xl * 2 - space.l;
  const map = useRef<SpotMapHandle>(null);
  const rail = useRef<FlatList<Place>>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const select = (id: string | null, from: 'map' | 'rail') => {
    setSelectedId(id);
    if (!id) return;
    const i = places.findIndex((p) => p.id === id);
    if (i < 0) return;
    if (from === 'map') rail.current?.scrollToIndex({ index: i, animated: true });
    map.current?.focus(places[i]);
  };

  // A place that was just added gets selected, so the user sees where it landed.
  useEffect(() => {
    if (!justSavedId || !places.some((p) => p.id === justSavedId)) return;
    clearJustSaved();
    const id = justSavedId;
    setTimeout(() => select(id, 'map'), 400);
  }, [justSavedId, places]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <View style={s.screen}>
      <SpotMap ref={map} places={places} here={here} selectedId={selectedId} onSelect={(id) => select(id, 'map')} />

      <View style={[s.top, { paddingTop: insets.top + space.s }]} pointerEvents="box-none">
        <View style={s.brand}>
          <Text style={s.wordmark}>SnapSpot</Text>
        </View>
        <View style={s.status} accessibilityRole="text">
          <View style={[s.dot, { backgroundColor: alertsOn ? color.green : color.amber }]} />
          <Text style={s.statusText}>{alertsOn ? `${watching}곳 지켜보는 중` : '알림 설정 필요'}</Text>
        </View>
      </View>

      <View style={s.bottom} pointerEvents="box-none">
        {places.length === 0 ? (
          <View style={[s.card, s.empty, { marginHorizontal: space.xl }]}>
            <Text style={type.title}>첫 장소를 꽂아 보세요</Text>
            <Text style={type.meta}>아래 + 버튼으로 맛집이나 팝업 스크린샷을 고르면, 장소를 찾아 이 지도에 핀으로 꽂아 드려요.</Text>
          </View>
        ) : (
          <FlatList
            ref={rail}
            data={places}
            horizontal
            keyExtractor={(p) => p.id}
            showsHorizontalScrollIndicator={false}
            snapToInterval={cardW + GAP}
            decelerationRate="fast"
            contentContainerStyle={{ paddingHorizontal: space.xl, gap: GAP }}
            getItemLayout={(_, i) => ({ length: cardW + GAP, offset: (cardW + GAP) * i, index: i })}
            onMomentumScrollEnd={(e) => {
              const i = Math.round(e.nativeEvent.contentOffset.x / (cardW + GAP));
              if (places[i] && places[i].id !== selectedId) select(places[i].id, 'rail');
            }}
            renderItem={({ item }) => (
              <RailCard
                place={item}
                width={cardW}
                distance={here ? distanceMeters(here, item) : null}
                selected={item.id === selectedId}
                onPress={() => select(item.id, 'rail')}
              />
            )}
          />
        )}
      </View>
    </View>
  );
}

function RailCard(props: { place: Place; width: number; distance: number | null; selected: boolean; onPress: () => void }) {
  const { place: p, distance } = props;
  const near = distance !== null && distance <= p.radius;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${p.name}, 지도에서 보기`}
      onPress={props.onPress}
      style={[s.card, { width: props.width }, props.selected && s.cardSelected]}
    >
      <Shot uri={p.imageUri} width={56} height={72} />
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={[type.title, { fontSize: 16, lineHeight: 22 }]} numberOfLines={1}>
          {p.name}
        </Text>
        <Text style={s.category}>{p.category}</Text>
        <Text style={type.meta} numberOfLines={1}>
          {p.address || '주소 없음'}
        </Text>
      </View>
      {near ? (
        <Text style={s.nearTag}>근처</Text>
      ) : (
        distance !== null && <Text style={s.distance}>{formatDistance(distance)}</Text>
      )}
    </Pressable>
  );
}

const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: color.paper },
  top: {
    position: 'absolute',
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: space.l,
  },
  brand: { backgroundColor: color.surface, borderRadius: 12, paddingHorizontal: space.m, paddingVertical: 6, borderWidth: 1, borderColor: color.line },
  wordmark: { fontFamily: font.display, fontSize: 20, lineHeight: 26, color: color.ink },
  status: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: color.surface,
    borderRadius: 999,
    paddingHorizontal: space.m,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: color.line,
  },
  dot: { width: 8, height: 8, borderRadius: 4 },
  statusText: { fontFamily: font.medium, fontSize: 13, color: color.ink },
  bottom: { position: 'absolute', left: 0, right: 0, bottom: space.l },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m,
    backgroundColor: color.surface,
    borderRadius: 16,
    padding: space.m,
    borderWidth: 1,
    borderColor: color.line,
  },
  cardSelected: { borderColor: color.ink, borderWidth: 1.5 },
  empty: { flexDirection: 'column', alignItems: 'flex-start', gap: space.xs, padding: space.l },
  category: { fontFamily: font.medium, fontSize: 13, lineHeight: 18, color: color.green },
  distance: { fontFamily: font.display, fontSize: 18, color: color.ink },
  nearTag: {
    fontFamily: font.display,
    fontSize: 15,
    color: color.ink,
    backgroundColor: color.highlight,
    paddingHorizontal: space.s,
    paddingVertical: 2,
    borderRadius: 6,
    overflow: 'hidden',
  },
});
