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

/** A franchise coupon: alerts at whichever branch of `brand` the user walks past. */
export type Coupon = {
  id: string;
  /** As written on the coupon, e.g. "스타벅스". Used as the Kakao search query. */
  brand: string;
  /** What the coupon gives, e.g. "아메리카노 1+1". */
  benefit: string;
  /** YYYY-MM-DD. Expired coupons stop alerting. */
  expiresAt: string | null;
  imageUri: string | null;
  createdAt: number;
  /** Set when the user marks it used; used coupons stop alerting. */
  usedAt: number | null;
  lastNotifiedAt: number | null;
};

export type Extracted = {
  /** What the screenshot is: a place to visit, or a franchise coupon. */
  kind: 'place' | 'coupon';
  /** Coupons only: the franchise brand and what the coupon gives. */
  brand: string;
  benefit: string;
  isPlace: boolean;
  name: string;
  address: string;
  category: string;
  note: string;
  endDate: string | null;
};
