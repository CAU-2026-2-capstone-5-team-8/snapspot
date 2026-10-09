import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Place } from './types';

const KEY = 'snapspot.places.v1';

export async function loadPlaces(): Promise<Place[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Place[]) : [];
  } catch {
    return [];
  }
}

export async function savePlaces(places: Place[]): Promise<void> {
  await AsyncStorage.setItem(KEY, JSON.stringify(places));
}

export async function upsertPlace(place: Place): Promise<Place[]> {
  const places = await loadPlaces();
  const next = [place, ...places.filter((p) => p.id !== place.id)];
  await savePlaces(next);
  return next;
}

export async function removePlace(id: string): Promise<Place[]> {
  const next = (await loadPlaces()).filter((p) => p.id !== id);
  await savePlaces(next);
  return next;
}

export async function markNotified(id: string, at: number): Promise<void> {
  const places = await loadPlaces();
  await savePlaces(places.map((p) => (p.id === id ? { ...p, lastNotifiedAt: at } : p)));
}
