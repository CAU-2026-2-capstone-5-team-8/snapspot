/**
 * Scores Kakao Local search results against what Gemini read from the screenshot.
 * Pure functions only (no React Native imports) so they run under `node --test` and eval scripts.
 */

/** The fields of a Kakao keyword-search document that we use. */
export type KakaoDoc = {
  id: string;
  place_name: string;
  category_name: string;
  category_group_code: string;
  address_name: string;
  road_address_name: string;
  x: string;
  y: string;
  place_url: string;
};

export type Extract = { name: string; address: string; category: string };

/** Gemini category → Kakao category_group_code values that agree with it. Missing = no opinion. */
const CATEGORY_GROUPS: Record<string, string[]> = {
  카페: ['CE7'],
  디저트: ['CE7', 'FD6'],
  식당: ['FD6'],
  술집: ['FD6'],
  전시: ['CT1'],
  관광: ['AT4', 'CT1'],
};

const WEIGHTS = { name: 0.6, address: 0.3, category: 0.1 };

export function normalizeName(s: string): string {
  return s
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}]+/gu, '')
    .replace(/점$/, '');
}

function bigrams(s: string): string[] {
  const out: string[] = [];
  for (let i = 0; i < s.length - 1; i++) out.push(s.slice(i, i + 2));
  return out;
}

/** Dice coefficient on character bigrams; containment of one name in the other counts as strong. */
export function nameSimilarity(a: string, b: string): number {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (!x || !y) return 0;
  if (x === y) return 1;
  const [short, long] = x.length <= y.length ? [x, y] : [y, x];
  if (short.length >= 2 && long.includes(short)) return 0.9;
  const bx = bigrams(x);
  const by = bigrams(y);
  if (!bx.length || !by.length) return 0;
  const pool = [...by];
  let hits = 0;
  for (const g of bx) {
    const i = pool.indexOf(g);
    if (i >= 0) {
      hits++;
      pool.splice(i, 1);
    }
  }
  return (2 * hits) / (bx.length + by.length);
}

const ADDRESS_NOISE = new Set(['서울', '서울시', '서울특별시', '대한민국']);

function addressTokens(address: string): string[] {
  return address
    .split(/[\s,]+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0 && !ADDRESS_NOISE.has(t));
}

/** Share of the screenshot's address tokens found in the candidate's road or lot address. null = no address to compare. */
export function addressSimilarity(extracted: string, doc: KakaoDoc): number | null {
  const tokens = addressTokens(extracted);
  if (!tokens.length) return null;
  const hay = `${doc.road_address_name} ${doc.address_name}`.replace(/\s+/g, ' ');
  const found = tokens.filter((t) => hay.includes(t)).length;
  return found / tokens.length;
}

export function categorySimilarity(category: string, doc: KakaoDoc): number | null {
  const groups = CATEGORY_GROUPS[category];
  if (!groups || !doc.category_group_code) return null;
  return groups.includes(doc.category_group_code) ? 1 : 0;
}

/** 0..1 confidence that `doc` is the place in the screenshot. Signals with no data are left out, not counted as 0. */
export function scoreCandidate(doc: KakaoDoc, ex: Extract): number {
  const parts: [number, number | null][] = [
    [WEIGHTS.name, nameSimilarity(ex.name, doc.place_name)],
    [WEIGHTS.address, addressSimilarity(ex.address, doc)],
    [WEIGHTS.category, categorySimilarity(ex.category, doc)],
  ];
  let sum = 0;
  let weight = 0;
  for (const [w, v] of parts) {
    if (v === null) continue;
    sum += w * v;
    weight += w;
  }
  return weight ? sum / weight : 0;
}

/**
 * "더현대 서울 B1 이벤트홀" → itself, "더현대 서울 B1", "더현대 서울", "더현대".
 * Screenshot names often carry floors/halls/event titles that Kakao does not index.
 */
export function nameVariants(name: string): string[] {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const out: string[] = [];
  for (let n = words.length; n >= 1; n--) out.push(words.slice(0, n).join(' '));
  return out;
}

/** "서울 성동구 아차산로9길 8" → "성동구": a district/neighborhood to narrow a keyword search. */
export function regionHint(address: string): string {
  const tokens = addressTokens(address).filter((t) => /(구|동|군|읍|면)$/.test(t));
  return tokens[0] ?? '';
}
