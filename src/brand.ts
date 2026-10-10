/**
 * Which Kakao search results are branches of a franchise. Pure, so it runs under node --test.
 *
 * Kakao puts the franchise at the end of category_name for chain stores
 * ("음식점 > 카페 > 커피전문점 > 스타벅스"), which separates real branches from shops that only
 * mention the brand ("하파크리스틴 올리브영N성수점" is a lens shop inside an Olive Young).
 */
import { nameSimilarity, normalizeName, type KakaoDoc } from './match.ts';

/** How alike the coupon's brand and a category tail must be ("메가커피" vs "메가MGC커피" ≈ 0.44). */
const MIN_BRAND_SIMILARITY = 0.4;

export function brandKey(brand: string): string {
  return normalizeName(brand);
}

function tail(doc: KakaoDoc): string {
  return doc.category_name.split('>').pop()?.trim() ?? '';
}

/**
 * The category tail that names this brand, if Kakao has one: it must resemble the brand,
 * and the shops filed under it must actually be named after it (rules out "제과,베이커리").
 */
function brandCategory(brand: string, docs: KakaoDoc[]): string | null {
  const groups = new Map<string, KakaoDoc[]>();
  for (const d of docs) {
    const t = tail(d);
    if (t) groups.set(t, [...(groups.get(t) ?? []), d]);
  }
  let best: { t: string; n: number } | null = null;
  for (const [t, members] of groups) {
    if (nameSimilarity(brand, t) < MIN_BRAND_SIMILARITY) continue;
    const key = normalizeName(t);
    const named = members.filter((m) => normalizeName(m.place_name).startsWith(key)).length;
    if (named * 2 < members.length) continue;
    if (!best || members.length > best.n) best = { t, n: members.length };
  }
  return best?.t ?? null;
}

/** Branches of `brand` among `docs`, in the given order. */
export function pickBranches(brand: string, docs: KakaoDoc[]): KakaoDoc[] {
  const cat = brandCategory(brand, docs);
  if (cat) return docs.filter((d) => tail(d) === cat);
  const key = brandKey(brand);
  return key ? docs.filter((d) => normalizeName(d.place_name).startsWith(key)) : [];
}
