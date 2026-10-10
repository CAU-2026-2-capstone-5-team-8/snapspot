/**
 * Screenshot text → real place, via Kakao Local search.
 * No React Native imports, so scripts can reuse it under plain Node.
 */
import { nameVariants, regionHint, scoreCandidate, type Extract, type KakaoDoc } from './match.ts';

export const KAKAO_KEY = process.env.EXPO_PUBLIC_KAKAO_REST_KEY ?? '';
const KEY = KAKAO_KEY;
const BASE = 'https://dapi.kakao.com/v2/local/search';

/** Below this score the top result is shown as a guess, not a confident match. */
export const CONFIDENT = 0.7;

export type Candidate = {
  kakaoId: string | null;
  name: string;
  address: string;
  category: string;
  latitude: number;
  longitude: number;
  placeUrl: string | null;
  score: number;
  source: 'keyword' | 'address';
};

export function hasKakaoKey(key = KEY): boolean {
  return key.length > 0;
}

export type Fetch = (url: string, init: { headers: Record<string, string> }) => Promise<Response>;

export async function kakaoGet<T>(path: string, params: Record<string, string>, key: string, fetcher: Fetch): Promise<T[]> {
  const qs = new URLSearchParams(params).toString();
  const res = await fetcher(`${BASE}/${path}.json?${qs}`, { headers: { Authorization: `KakaoAK ${key}` } });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`카카오 검색 실패 (${res.status}): ${text.slice(0, 200)}`);
  }
  const json = (await res.json()) as { documents?: T[] };
  return json.documents ?? [];
}

type AddressDoc = { address_name: string; x: string; y: string; road_address: { address_name: string } | null };

/**
 * Candidates sorted by confidence (best first). Searches "district + name" and the name alone,
 * shortening the name word by word until a confident match shows up, then the address itself
 * (gives coordinates even when the shop is not listed).
 */
export async function searchPlace(ex: Extract, key = KEY, fetcher: Fetch = fetch): Promise<Candidate[]> {
  if (!key) throw new Error('카카오 REST API 키가 없어요. .env의 EXPO_PUBLIC_KAKAO_REST_KEY를 설정하세요.');
  const region = regionHint(ex.address);

  const seen = new Map<string, KakaoDoc>();
  for (const name of nameVariants(ex.name)) {
    const queries = [[region, name].filter(Boolean).join(' '), name].filter((q, i, arr) => arr.indexOf(q) === i);
    for (const query of queries) {
      for (const d of await kakaoGet<KakaoDoc>('keyword', { query, size: '10' }, key, fetcher)) {
        if (!seen.has(d.id)) seen.set(d.id, d);
      }
    }
    // Any hit is not enough: "더현대 서울 B1" matches unrelated "B1" shops. Keep shortening until one fits.
    if ([...seen.values()].some((d) => scoreCandidate(d, ex) >= CONFIDENT)) break;
  }
  const out: Candidate[] = [...seen.values()].map((d) => ({
    kakaoId: d.id,
    name: d.place_name,
    address: d.road_address_name || d.address_name,
    category: d.category_name.split(' > ').pop() ?? '',
    latitude: Number(d.y),
    longitude: Number(d.x),
    placeUrl: d.place_url || null,
    score: scoreCandidate(d, ex),
    source: 'keyword',
  }));

  if (ex.address.trim()) {
    const [a] = await kakaoGet<AddressDoc>('address', { query: ex.address, size: '1' }, key, fetcher);
    if (a) {
      out.push({
        kakaoId: null,
        name: ex.name || a.address_name,
        address: a.road_address?.address_name || a.address_name,
        category: '',
        latitude: Number(a.y),
        longitude: Number(a.x),
        placeUrl: null,
        // An exact address is solid for location even though we cannot confirm the shop itself.
        score: 0.65,
        source: 'address',
      });
    }
  }
  return out.sort((a, b) => b.score - a.score);
}
