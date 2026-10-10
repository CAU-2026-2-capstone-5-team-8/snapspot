import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PlacesProvider } from '../state/places';
import { color } from '../ui/theme';

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <PlacesProvider>
        <StatusBar style="dark" />
        <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: color.paper } }}>
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="add" options={{ presentation: 'modal', animation: 'slide_from_bottom' }} />
        </Stack>
      </PlacesProvider>
    </SafeAreaProvider>
  );
}
