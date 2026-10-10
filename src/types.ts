export type Place = {
  id: string;
  name: string;
  address: string;
  category: string;
  note: string;
  /** YYYY-MM-DD. Pop-ups and exhibitions end; after this date we stop alerting. */
  endDate: string | null;
  latitude: number;
  longitude: number;
  /** Geofence radius in meters. */
  radius: number;
  imageUri: string | null;
  /** Set when the place was matched through Kakao Local search. */
  kakaoId?: string | null;
  placeUrl?: string | null;
  createdAt: number;
  lastNotifiedAt: number | null;
};

export type Extracted = {
  isPlace: boolean;
  name: string;
  address: string;
  category: string;
  note: string;
  endDate: string | null;
};
