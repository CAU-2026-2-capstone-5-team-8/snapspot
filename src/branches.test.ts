// Run: npm test
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CACHE_TTL_MS, cellOf, memoryCache, nearbyBranches } from './branches.ts';

const here = { latitude: 37.5446, longitude: 127.0565 };

function fakeKakao(docs: { name: string; cat: string }[]) {
  let calls = 0;
  const fetcher = async () => {
    calls++;
    const documents = docs.map((d, i) => ({
      id: String(i),
      place_name: d.name,
      category_name: d.cat,
      category_group_code: '',
      address_name: '',
      road_address_name: '서울 성동구',
      x: '127.05',
      y: '37.54',
      place_url: '',
    }));
    return new Response(JSON.stringify({ documents }), { status: 200 });
  };
  return { fetcher, calls: () => calls };
}

test('filters to real branches', async () => {
  const k = fakeKakao([
    { name: '올리브영 성수역점', cat: '가정,생활 > 드럭스토어 > 올리브영' },
    { name: '하파크리스틴 올리브영N성수점', cat: '가정,생활 > 패션 > 하파크리스틴' },
  ]);
  const r = await nearbyBranches('올리브영', here, { cache: memoryCache(), key: 'k', fetcher: k.fetcher });
  assert.deepEqual(r.branches.map((b) => b.name), ['올리브영 성수역점']);
  assert.equal(r.requests, 1);
});

test('second lookup in the same cell is served from cache; a stale entry is refetched', async () => {
  const k = fakeKakao([{ name: '스타벅스 성수점', cat: '음식점 > 카페 > 커피전문점 > 스타벅스' }]);
  const cache = memoryCache();
  const opts = { cache, key: 'k', fetcher: k.fetcher, now: 1_000 };
  await nearbyBranches('스타벅스', here, opts);
  const nearby = { latitude: here.latitude + 0.0001, longitude: here.longitude + 0.0001 };
  assert.equal(cellOf(nearby).key, cellOf(here).key);
  const again = await nearbyBranches('스타벅스', nearby, opts);
  assert.equal(again.requests, 0);
  assert.equal(k.calls(), 1);
  const later = await nearbyBranches('스타벅스', here, { ...opts, now: 1_000 + CACHE_TTL_MS + 1 });
  assert.equal(later.requests, 1);
});

test('stops paging when a page is not full', async () => {
  const k = fakeKakao([{ name: '스타벅스 성수점', cat: '음식점 > 카페 > 커피전문점 > 스타벅스' }]);
  await nearbyBranches('스타벅스', here, { cache: memoryCache(), key: 'k', fetcher: k.fetcher });
  assert.equal(k.calls(), 1);
});
