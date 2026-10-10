// Run: npm test
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { brandKey, pickBranches } from './brand.ts';
import type { KakaoDoc } from './match.ts';

const d = (place_name: string, category_name: string, id = place_name): KakaoDoc => ({
  id,
  place_name,
  category_name,
  category_group_code: '',
  address_name: '',
  road_address_name: '',
  x: '127.05',
  y: '37.54',
  place_url: '',
});

test('keeps real branches via the brand at the end of category_name (real 올리브영 results, 2026-10-10)', () => {
  const docs = [
    d('올리브영 뷰티 맨션 성수', '가정,생활 > 드럭스토어 > 올리브영'),
    d('하파크리스틴 올리브 영 성수 뷰티맨션점', '가정,생활 > 패션 > 패션잡화점 > 안경,렌즈 > 하파크리스틴'),
    d('투쿨포스쿨 올리브영N 성수 팝업', '서비스,산업 > 마케팅 > 상품전시,매장전시'),
    d('올리브영 N성수', '가정,생활 > 드럭스토어 > 올리브영'),
    d('올리브영 성수역점', '가정,생활 > 드럭스토어 > 올리브영'),
  ];
  assert.deepEqual(
    pickBranches('올리브영', docs).map((x) => x.place_name),
    ['올리브영 뷰티 맨션 성수', '올리브영 N성수', '올리브영 성수역점'],
  );
});

test('follows Kakao alias when the coupon name differs (메가커피 → 메가MGC커피)', () => {
  const docs = [
    d('메가MGC커피 성수역점', '음식점 > 카페 > 커피전문점 > 메가MGC커피'),
    d('메가MGC커피 뚝섬역점', '음식점 > 카페 > 커피전문점 > 메가MGC커피'),
    d('메가MGC커피 서울숲역점', '음식점 > 카페 > 커피전문점 > 메가MGC커피'),
  ];
  assert.equal(pickBranches('메가커피', docs).length, 3);
});

test('falls back to name prefix when Kakao has no brand category', () => {
  const docs = [
    d('동네빵집 성수점', '음식점 > 간식 > 제과,베이커리'),
    d('동네빵집 뚝섬점', '음식점 > 간식 > 제과,베이커리'),
    d('옆집베이커리', '음식점 > 간식 > 제과,베이커리'),
  ];
  assert.deepEqual(pickBranches('동네빵집', docs).map((x) => x.place_name), ['동네빵집 성수점', '동네빵집 뚝섬점']);
});

test('does not let a generic category pass as a brand', () => {
  const docs = [
    d('카페A', '음식점 > 카페'),
    d('카페B', '음식점 > 카페'),
    d('스타벅스 성수점', '음식점 > 카페 > 커피전문점 > 스타벅스'),
  ];
  assert.deepEqual(pickBranches('스타벅스', docs).map((x) => x.place_name), ['스타벅스 성수점']);
});

test('brandKey is stable across spacing and case', () => {
  assert.equal(brandKey(' Starbucks '), brandKey('starbucks'));
  assert.equal(brandKey('올리브 영'), brandKey('올리브영'));
});
