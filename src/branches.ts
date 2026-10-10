/**
 * Nearby branches of a franchise, from Kakao Local search, cached per ~1km grid cell.
 * No React Native imports: the app passes an AsyncStorage-backed cache, tests and the
 * simulator pass an in-memory one.
 */
import { brandKey, pickBranches } from './brand.ts';
import type { Coords } from './distance.ts';
import { KAKAO_KEY, kakaoGet, type Fetch } from './kakao.ts';
import type { KakaoDoc } from './match.ts';

/** Search radius around a cell center. Covers the cell plus the refresh radius. */
export const SEARCH_RADIUS_M = 2500;
/** Grid cell size in degrees (~1.1km north-south, ~0.9km east-west in Seoul). */
const CELL_DEG = 0.01;
/** Keep results briefly: branches rarely move, and Kakao's terms discourage long-term storage. */
export const CACHE_TTL_MS = 6 * 60 * 60 * 1000;
/** Kakao returns at most 45 results (3 pages × 15) per query. */
const MAX_PAGES = 3;

export type Branch = Coords & { kakaoId: string; name: string; address: string };

export type BranchCache = {
  get(key: string): Promise<{ at: number; branches: Branch[] } | null>;
  set(key: string, value: { at: number; branches: Branch[] }): Promise<void>;
};

export function memoryCache(): BranchCache {
  const m = new Map<string, { at: number; branches: Branch[] }>();
  return { get: async (k) => m.get(k) ?? null, set: async (k, v) => void m.set(k, v) };
}

export function cellOf(p: Coords): { key: string; center: Coords } {
  const i = Math.floor(p.latitude / CELL_DEG);
  const j = Math.floor(p.longitude / CELL_DEG);
  return { key: `${i}:${j}`, center: { latitude: (i + 0.5) * CELL_DEG, longitude: (j + 0.5) * CELL_DEG } };
}

export type BranchResult = { branches: Branch[]; requests: number };

/** Branches of `brand` near `here`. `requests` counts Kakao calls made (0 on a cache hit). */
export async function nearbyBranches(
  brand: string,
  here: Coords,
  opts: { cache: BranchCache; key?: string; fetcher?: Fetch; now?: number },
): Promise<BranchResult> {
  const key = opts.key ?? KAKAO_KEY;
  const now = opts.now ?? Date.now();
  const cell = cellOf(here);
  const cacheKey = `${brandKey(brand)}|${cell.key}`;
  const hit = await opts.cache.get(cacheKey);
  if (hit && now - hit.at < CACHE_TTL_MS) return { branches: hit.branches, requests: 0 };

  const docs: KakaoDoc[] = [];
  let requests = 0;
  for (let page = 1; page <= MAX_PAGES; page++) {
    const batch = await kakaoGet<KakaoDoc>(
      'keyword',
      {
        query: brand,
        x: String(cell.center.longitude),
        y: String(cell.center.latitude),
        radius: String(SEARCH_RADIUS_M),
        sort: 'distance',
        size: '15',
        page: String(page),
      },
      key,
      opts.fetcher ?? fetch,
    );
    requests++;
    docs.push(...batch);
    if (batch.length < 15) break;
  }
  const branches = pickBranches(brand, docs).map((d) => ({
    kakaoId: d.id,
    name: d.place_name,
    address: d.road_address_name || d.address_name,
    latitude: Number(d.y),
    longitude: Number(d.x),
  }));
  await opts.cache.set(cacheKey, { at: now, branches });
  return { branches, requests };
}
