/**
 * Place-matching accuracy on labeled screenshots (README "신뢰도 측정 계획").
 *
 *   node --env-file=.env scripts/eval-match.ts eval/labels.json
 *
 * labels.json: [{ "image": "cafe_post.jpg", "name": "어니언 성수", "lat": 37.5447, "lng": 127.0582 }, ...]
 * Image paths are relative to the labels file.
 * Compares Kakao matching against an address-only baseline (what a plain geocoder can do).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { extractPlace } from '../src/gemini.ts';
import { searchPlace, type Candidate } from '../src/kakao.ts';
import { normalizeName } from '../src/match.ts';

type Label = { image: string; name: string; lat: number; lng: number };

const NEAR_M = 100;

function meters(a: { latitude: number; longitude: number }, lat: number, lng: number): number {
  const R = 6371000;
  const rad = (d: number) => (d * Math.PI) / 180;
  const h =
    Math.sin(rad(lat - a.latitude) / 2) ** 2 +
    Math.cos(rad(a.latitude)) * Math.cos(rad(lat)) * Math.sin(rad(lng - a.longitude) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function median(xs: number[]): number {
  if (!xs.length) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
}

const file = process.argv[2];
if (!file) throw new Error('usage: node --env-file=.env scripts/eval-match.ts <labels.json>');
const labels = JSON.parse(readFileSync(file, 'utf8')) as Label[];

const rows: { image: string; kakao: Candidate | null; address: Candidate | null; nameOk: boolean }[] = [];
for (const l of labels) {
  const b64 = readFileSync(join(dirname(file), l.image)).toString('base64');
  const ex = await extractPlace(b64);
  const cands = await searchPlace(ex).catch((e) => {
    console.error(l.image, e instanceof Error ? e.message : e);
    return [] as Candidate[];
  });
  const kakao = cands[0] ?? null;
  const address = cands.find((c) => c.source === 'address') ?? null;
  const nameOk = !!kakao && kakao.source === 'keyword' && normalizeName(kakao.name).includes(normalizeName(l.name));
  rows.push({ image: l.image, kakao, address, nameOk });
  const err = kakao ? `${Math.round(meters(kakao, l.lat, l.lng))}m` : '-';
  console.log(`${l.image}\t→ ${kakao?.name ?? '(없음)'} [${kakao ? Math.round(kakao.score * 100) : 0}%] 오차 ${err}`);
}

const errs = (pick: (r: (typeof rows)[number]) => Candidate | null) =>
  rows.map((r, i) => {
    const c = pick(r);
    return c ? meters(c, labels[i].lat, labels[i].lng) : Infinity;
  });
const report = (label: string, e: number[]) =>
  console.log(
    `${label}\t${NEAR_M}m 이내 ${e.filter((x) => x <= NEAR_M).length}/${e.length}` +
      `\t찾음 ${e.filter(Number.isFinite).length}/${e.length}\t중앙 오차 ${Math.round(median(e.filter(Number.isFinite)))}m`,
  );

console.log('\n== 결과 ==');
console.log(`장소명 일치 (카카오 1순위)\t${rows.filter((r) => r.nameOk).length}/${rows.length}`);
report('카카오 매칭', errs((r) => r.kakao));
report('주소만 (기준선)', errs((r) => r.address));
