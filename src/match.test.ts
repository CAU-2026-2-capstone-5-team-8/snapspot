// Run: node --test src/
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { nameVariants, normalizeName, regionHint, scoreCandidate, type KakaoDoc } from './match.ts';

const doc = (over: Partial<KakaoDoc>): KakaoDoc => ({
  id: '1',
  place_name: '어니언 성수',
  category_name: '음식점 > 카페',
  category_group_code: 'CE7',
  address_name: '서울 성동구 성수동2가 277-135',
  road_address_name: '서울 성동구 아차산로9길 8',
  x: '127.0582',
  y: '37.5447',
  place_url: 'http://place.map.kakao.com/1',
  ...over,
});

test('normalizeName drops spaces, punctuation and a trailing 점', () => {
  assert.equal(normalizeName('어니언 성수점'), '어니언성수');
  assert.equal(normalizeName('Cafe ONION (Seongsu)'), 'cafeonionseongsu');
});

test('regionHint keeps 구/동 tokens only', () => {
  assert.equal(regionHint('서울 성동구 아차산로9길 8'), '성동구');
  assert.equal(regionHint('성수동'), '성수동');
  assert.equal(regionHint(''), '');
});

test('nameVariants drops trailing words, longest first', () => {
  assert.deepEqual(nameVariants('더현대 서울 B1 이벤트홀'), ['더현대 서울 B1 이벤트홀', '더현대 서울 B1', '더현대 서울', '더현대']);
  assert.deepEqual(nameVariants('어니언'), ['어니언']);
  assert.deepEqual(nameVariants('  '), []);
});

test('exact name + address + category scores near 1', () => {
  const s = scoreCandidate(doc({}), { name: '어니언 성수', address: '서울 성동구 아차산로9길 8', category: '카페' });
  assert.ok(s > 0.95, `got ${s}`);
});

test('same brand, other branch loses to the right branch via address', () => {
  const ex = { name: '어니언', address: '서울 성동구 아차산로9길 8', category: '카페' };
  const right = scoreCandidate(doc({}), ex);
  const wrong = scoreCandidate(
    doc({ place_name: '어니언 안국', road_address_name: '서울 종로구 계동길 5', address_name: '서울 종로구 계동 146-1' }),
    ex,
  );
  assert.ok(right > wrong + 0.2, `right ${right} wrong ${wrong}`);
});

test('venue name contained in a longer extracted name still matches', () => {
  const s = scoreCandidate(
    doc({ place_name: '더현대 서울', category_group_code: '', road_address_name: '서울 영등포구 여의대로 108' }),
    { name: '더현대 서울 B1 이벤트홀', address: '서울 영등포구 여의대로 108', category: '팝업' },
  );
  assert.ok(s > 0.8, `got ${s}`);
});

test('unrelated place scores low', () => {
  const s = scoreCandidate(
    doc({ place_name: '김밥천국 성수점', category_group_code: 'FD6', road_address_name: '서울 성동구 왕십리로 1' }),
    { name: '어니언 성수', address: '서울 성동구 아차산로9길 8', category: '카페' },
  );
  assert.ok(s < 0.4, `got ${s}`);
});

test('missing address/category does not penalize a name match', () => {
  const s = scoreCandidate(doc({}), { name: '어니언 성수', address: '', category: '기타' });
  assert.ok(s > 0.9, `got ${s}`);
});
