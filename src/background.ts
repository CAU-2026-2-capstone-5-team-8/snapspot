/**
 * Background work. This file must be imported from index.ts BEFORE the app registers,
 * so the tasks exist when Android/iOS wakes the app up in the background.
 */
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { Platform } from 'react-native';
import { distanceMeters, type Coords } from './geo';
import { notifyIfAllowed, suppressReason } from './notify';
import { loadPlaces } from './storage';

export const GEOFENCE_TASK = 'snapspot-geofence';
export const REFRESH_TASK = 'snapspot-refresh-nearest';

/** OS limits: iOS monitors at most 20 regions, Android 100. Keep a margin on Android. */
export const MAX_REGIONS = Platform.OS === 'ios' ? 20 : 90;

type GeofenceData = { eventType: Location.GeofencingEventType; region: Location.LocationRegion };

try {
  TaskManager.defineTask<GeofenceData>(GEOFENCE_TASK, async ({ data, error }) => {
    if (error || !data) return;
    if (data.eventType === Location.GeofencingEventType.Enter && data.region.identifier) {
      await notifyIfAllowed(data.region.identifier);
    }
  });

  TaskManager.defineTask<{ locations: Location.LocationObject[] }>(REFRESH_TASK, async ({ data, error }) => {
    if (error || !data?.locations?.length) return;
    const { latitude, longitude } = data.locations[data.locations.length - 1].coords;
    await syncGeofences({ latitude, longitude });
  });
} catch {
  // Expo Go on Android has no TaskManager; the in-app watcher still works there.
}

/**
 * Register geofences for the places nearest to `here` (or the first N if unknown).
 * When there are more saved places than the OS allows, a low-frequency location task
 * keeps swapping in the nearest ones as the user moves.
 */
export async function syncGeofences(here?: Coords | null): Promise<{ active: number; total: number }> {
  const places = (await loadPlaces()).filter((p) => suppressReason(p) !== '운영 종료');
  const sorted = here
    ? [...places].sort((a, b) => distanceMeters(here, a) - distanceMeters(here, b))
    : places;
  const chosen = sorted.slice(0, MAX_REGIONS);

  const available = await TaskManager.isAvailableAsync().catch(() => false);
  if (!available) return { active: 0, total: places.length };

  if (chosen.length === 0) {
    if (await Location.hasStartedGeofencingAsync(GEOFENCE_TASK).catch(() => false)) {
      await Location.stopGeofencingAsync(GEOFENCE_TASK);
    }
  } else {
    await Location.startGeofencingAsync(
      GEOFENCE_TASK,
      chosen.map((p) => ({
        identifier: p.id,
        latitude: p.latitude,
        longitude: p.longitude,
        radius: p.radius,
        notifyOnEnter: true,
        notifyOnExit: false,
      })),
    );
  }

  const needsRotation = places.length > MAX_REGIONS;
  const running = await Location.hasStartedLocationUpdatesAsync(REFRESH_TASK).catch(() => false);
  if (needsRotation && !running) {
    await Location.startLocationUpdatesAsync(REFRESH_TASK, {
      accuracy: Location.Accuracy.Balanced,
      distanceInterval: 500,
      deferredUpdatesDistance: 500,
      foregroundService: {
        notificationTitle: 'SnapSpot',
        notificationBody: '저장한 장소 근처를 살피는 중이에요',
      },
    });
  } else if (!needsRotation && running) {
    await Location.stopLocationUpdatesAsync(REFRESH_TASK);
  }
  return { active: chosen.length, total: places.length };
}

export async function backgroundSupported(): Promise<boolean> {
  return TaskManager.isAvailableAsync().catch(() => false);
}
