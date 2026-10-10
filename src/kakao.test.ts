// Run: npm test
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { searchPlace } from './kakao.ts';
import type { KakaoDoc } from './match.ts';

const doc = (id: string, place_name: string, road: string, code = ''): KakaoDoc => ({
  id,
  place_name,
  category_name: '',
  category_group_code: code,
  address_name: road,
  road_address_name: road,
  x: '126.9',
  y: '37.5',
  place_url: `http://place.map.kakao.com/${id}`,
});

/** Fake Kakao: answers by exact query text, records what was asked. */
function fakeKakao(byQuery: Record<string, KakaoDoc[]>) {
  const asked: string[] = [];
  const fetcher = async (url: string) => {
    const u = new URL(url);
    const q = u.searchParams.get('query') ?? '';
    asked.push(`${u.pathname.endsWith('address.json') ? 'addr' : 'kw'}:${q}`);
    const documents = u.pathname.endsWith('address.json') ? [] : (byQuery[q] ?? []);
    return new Response(JSON.stringify({ documents }), { status: 200 });
  };
  return { fetcher, asked };
}

test('keeps shortening past noisy hits until a confident match (더현대 서울 B1 regression)', async () => {
  const { fetcher, asked } = fakeKakao({
    // Real Kakao behavior on 2026-10-10: "B1" matches unrelated places.
    '더현대 서울 B1': [doc('9', '페어몬트앰배서더서울웨딩', '서울 영등포구 여의대로 108'), doc('8', 'B1 홍대', '서울 마포구 와우산로 1')],
    '영등포구 더현대 서울': [doc('1', '더현대 서울', '서울 영등포구 여의대로 108')],
  });
  const out = await searchPlace(
    { name: '더현대 서울 B1 이벤트홀', address: '서울 영등포구 여의대로 108', category: '팝업' },
    'k',
    fetcher,
  );
  assert.equal(out[0].name, '더현대 서울');
  assert.ok(out[0].score >= 0.7, `score ${out[0].score}`);
  assert.ok(!asked.includes('kw:더현대'), 'should stop once confident');
});

test('stops at the full name when it already matches', async () => {
  const { fetcher, asked } = fakeKakao({
    '성동구 어니언 성수': [doc('1', '어니언 성수', '서울 성동구 아차산로9길 8', 'CE7')],
  });
  const out = await searchPlace({ name: '어니언 성수', address: '서울 성동구 아차산로9길 8', category: '카페' }, 'k', fetcher);
  assert.equal(out[0].name, '어니언 성수');
  assert.deepEqual(asked.filter((a) => a.startsWith('kw:')), ['kw:성동구 어니언 성수', 'kw:어니언 성수']);
});
