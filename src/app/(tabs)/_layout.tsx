import { Feather } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { TabList, TabSlot, TabTrigger, Tabs } from 'expo-router/ui';
import { forwardRef } from 'react';
import { Pressable, StyleSheet, Text, View, type PressableProps } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePlaces } from '../../state/places';
import { color, font, space } from '../../ui/theme';

type TabButtonProps = PressableProps & { isFocused?: boolean; icon: keyof typeof Feather.glyphMap; label: string };

const TabButton = forwardRef<View, TabButtonProps>(function TabButton({ isFocused, icon, label, ...rest }, ref) {
  return (
    <Pressable
      ref={ref}
      {...rest}
      accessibilityRole="tab"
      accessibilityState={{ selected: !!isFocused }}
      style={({ pressed }) => [t.tab, pressed && { opacity: 0.6 }]}
    >
      <Feather name={icon} size={22} color={isFocused ? color.ink : color.muted} />
      <Text style={[t.label, isFocused && t.labelOn]}>{label}</Text>
    </Pressable>
  );
});

function AddButton() {
  const router = useRouter();
  const { pickScreenshot } = usePlaces();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="스크린샷에서 장소 추가"
      onPress={async () => {
        if (await pickScreenshot()) router.push('/add');
      }}
      style={({ pressed }) => [t.add, pressed && { transform: [{ scale: 0.95 }] }]}
    >
      <Feather name="plus" size={28} color={color.surface} />
    </Pressable>
  );
}

export default function TabsLayout() {
  const insets = useSafeAreaInsets();
  return (
    <Tabs>
      <TabSlot />
      {/* Routes are declared here; the visible bar below uses custom buttons. */}
      <TabList style={{ display: 'none' }}>
        <TabTrigger name="map" href="/" />
        <TabTrigger name="list" href="/list" />
      </TabList>
      <View style={[t.bar, { paddingBottom: Math.max(insets.bottom, space.s) }]}>
        <TabTrigger name="map" asChild>
          <TabButton icon="map" label="지도" />
        </TabTrigger>
        <AddButton />
        <TabTrigger name="list" asChild>
          <TabButton icon="list" label="목록" />
        </TabTrigger>
      </View>
    </Tabs>
  );
}

const t = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    backgroundColor: color.surface,
    borderTopWidth: 1,
    borderTopColor: color.line,
    paddingTop: space.s,
  },
  tab: { alignItems: 'center', gap: 2, minWidth: 72, minHeight: 48, justifyContent: 'center' },
  label: { fontFamily: font.medium, fontSize: 12, color: color.muted },
  labelOn: { color: color.ink, fontFamily: font.bold },
  add: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: color.green,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: -28,
    borderWidth: 4,
    borderColor: color.surface,
  },
});
