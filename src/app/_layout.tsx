import * as Notifications from 'expo-notifications';
import { Stack, useRouter } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PlacesProvider } from '../state/places';
import { color } from '../ui/theme';

/** Tapping a coupon alert opens that coupon, ready to show at the counter — also from a cold start. */
function useOpenCouponFromNotification() {
  const router = useRouter();
  const response = Notifications.useLastNotificationResponse();
  useEffect(() => {
    if (!response || response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return;
    const couponId = response.notification.request.content.data?.couponId;
    if (typeof couponId === 'string') router.push(`/coupon/${couponId}`);
  }, [response]); // eslint-disable-line react-hooks/exhaustive-deps
}

function Root() {
  useOpenCouponFromNotification();
  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: color.paper } }}>
      <Stack.Screen name="(tabs)" />
      <Stack.Screen name="add" options={{ presentation: 'modal', animation: 'slide_from_bottom' }} />
      <Stack.Screen name="coupon/[id]" options={{ presentation: 'modal', animation: 'slide_from_bottom' }} />
    </Stack>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <PlacesProvider>
        <StatusBar style="dark" />
        <Root />
      </PlacesProvider>
    </SafeAreaProvider>
  );
}
