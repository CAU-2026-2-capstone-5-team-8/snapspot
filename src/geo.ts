import * as Location from 'expo-location';

export { distanceMeters, formatDistance, type Coords } from './distance.ts';
import type { Coords } from './distance.ts';

/**
 * Turn the extracted name/address into coordinates with the device geocoder.
 * Tries the most specific query first.
 */
export async function geocodePlace(name: string, address: string): Promise<Coords | null> {
  const queries = [
    [address, name].filter(Boolean).join(' '),
    address,
    name,
  ].filter((q, i, arr) => q && arr.indexOf(q) === i);
  for (const q of queries) {
    try {
      const results = await Location.geocodeAsync(q);
      if (results.length > 0) {
        return { latitude: results[0].latitude, longitude: results[0].longitude };
      }
    } catch {
      // try the next query
    }
  }
  return null;
}
