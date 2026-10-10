import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { loadPlaces, markNotified } from './storage';
import type { Place } from './types';

export const CHANNEL_ID = 'nearby-places';
/** Do not alert about the same place more than once in this window. */
export const COOLDOWN_MS = 6 * 60 * 60 * 1000;

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

export async function setupNotifications(): Promise<boolean> {
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync(CHANNEL_ID, {
      name: '근처 저장 장소 알림',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
    });
  }
  const current = await Notifications.getPermissionsAsync();
  if (current.granted) return true;
  const asked = await Notifications.requestPermissionsAsync();
  return asked.granted;
}

/** Read-only check, for refreshing status without prompting again. */
export async function notificationsGranted(): Promise<boolean> {
  return (await Notifications.getPermissionsAsync()).granted;
}

/** Why a place should NOT alert right now, or null if it should. */
export function suppressReason(place: Place, now = Date.now()): string | null {
  if (place.endDate) {
    const end = new Date(`${place.endDate}T23:59:59`);
    if (!Number.isNaN(end.getTime()) && end.getTime() < now) return '운영 종료';
  }
  if (place.lastNotifiedAt && now - place.lastNotifiedAt < COOLDOWN_MS) return '최근에 알림';
  return null;
}

async function show(place: Place): Promise<void> {
  const extra = place.note ? ` · ${place.note}` : '';
  await Notifications.scheduleNotificationAsync({
    content: {
      title: `📍 저장해 둔 ${place.category} 근처예요`,
      body: `${place.name}${extra}`,
      data: { placeId: place.id },
    },
    trigger: Platform.OS === 'android' ? { channelId: CHANNEL_ID } : null,
  });
}

/**
 * Geofence Enter events often arrive in pairs, and the in-app watcher can fire at the same time.
 * Run check → show → mark one at a time so the cooldown sees the previous alert.
 */
let queue: Promise<unknown> = Promise.resolve();

/** Called from the geofence task (app may be closed) and from the in-app watcher. */
export function notifyIfAllowed(placeId: string, opts: { force?: boolean } = {}): Promise<boolean> {
  const run = queue.then(async () => {
    const place = (await loadPlaces()).find((p) => p.id === placeId);
    if (!place) return false;
    if (!opts.force && suppressReason(place)) return false;
    await show(place);
    await markNotified(place.id, Date.now());
    return true;
  });
  queue = run.catch(() => undefined);
  return run;
}
