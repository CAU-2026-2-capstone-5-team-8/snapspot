import * as Location from 'expo-location';

export type Coords = { latitude: number; longitude: number };

/** Haversine distance in meters. */
export function distanceMeters(a: Coords, b: Coords): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.latitude - a.latitude);
  const dLng = toRad(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.latitude)) * Math.cos(toRad(b.latitude)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

export function formatDistance(m: number): string {
  return m < 1000 ? `${Math.round(m)}m` : `${(m / 1000).toFixed(1)}km`;
}

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
