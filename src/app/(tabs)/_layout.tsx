import { Feather } from '@expo/vector-icons';
import { usePathname, useRouter } from 'expo-router';
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
  // On Android, touches on a modal screen (add, coupon) also reach this bar underneath it, so a
  // button at the bottom of the modal could switch tabs too. Ignore touches while a modal is up.
  const path = usePathname();
  const modalOpen = path.startsWith('/add') || path.startsWith('/coupon/');
  return (
    <Tabs>
      <TabSlot />
      {/* Routes are declared here; the visible bar below uses custom buttons. */}
      <TabList style={{ display: 'none' }}>
        <TabTrigger name="map" href="/" />
        <TabTrigger name="coupons" href="/coupons" />
        <TabTrigger name="list" href="/list" />
        <TabTrigger name="status" href="/status" />
      </TabList>
      <View
        style={[t.bar, { paddingBottom: Math.max(insets.bottom, space.s) }]}
        pointerEvents={modalOpen ? 'none' : 'auto'}
        importantForAccessibility={modalOpen ? 'no-hide-descendants' : 'auto'}
      >
        <TabTrigger name="map" asChild>
          <TabButton icon="map" label="지도" />
        </TabTrigger>
        <TabTrigger name="coupons" asChild>
          <TabButton icon="tag" label="쿠폰함" />
        </TabTrigger>
        <AddButton />
        <TabTrigger name="list" asChild>
          <TabButton icon="bookmark" label="장소" />
        </TabTrigger>
        <TabTrigger name="status" asChild>
          <TabButton icon="activity" label="현황" />
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
  tab: { alignItems: 'center', gap: 2, minWidth: 60, minHeight: 48, justifyContent: 'center' },
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
