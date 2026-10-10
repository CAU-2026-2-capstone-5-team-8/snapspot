/**
 * How many branch visits does each geofence strategy catch, and at what cost?
 *
 *   node scripts/simulate-window.ts            # prints a markdown table
 *   node scripts/simulate-window.ts --write    # also writes docs/simulation.md
 *
 * Drives the real planner (src/window.ts) over synthetic trips through a city with
 * franchise branches at Seoul-like densities. A visit = the user inside a branch's alert
 * radius for a stretch of time; it counts as caught if that branch was registered with the
 * OS at any moment of the visit (Android fires an initial ENTER for regions you are already in).
 */
import { writeFileSync } from 'node:fs';
import { cellOf, SEARCH_RADIUS_M } from '../src/branches.ts';
import { distanceMeters, type Coords } from '../src/distance.ts';
import { planRegions, REFRESH_ID, type Spot } from '../src/window.ts';

// ---- world -------------------------------------------------------------------------------
const ORIGIN = { latitude: 37.5, longitude: 126.95 };
const SIZE_M = 12_000;
const M_PER_DEG_LAT = 111_320;
const M_PER_DEG_LNG = 111_320 * Math.cos((ORIGIN.latitude * Math.PI) / 180);
const BRANCH_RADIUS_M = 200;
/** Kakao returns at most this many results per query (3 pages × 15). */
const KAKAO_CAP = 45;

/** Seeded RNG so every run gives the same table. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = rng(20261010);
const at = (x: number, y: number): Coords => ({
  latitude: ORIGIN.latitude + y / M_PER_DEG_LAT,
  longitude: ORIGIN.longitude + x / M_PER_DEG_LNG,
});

/** Branch densities per km², roughly Starbucks / Mega Coffee / Olive Young in central Seoul. */
const BRANDS = [
  { key: 'starbucks', perKm2: 1.6 },
  { key: 'megacoffee', perKm2: 3.0 },
  { key: 'oliveyoung', perKm2: 1.2 },
];
const AREA_KM2 = (SIZE_M / 1000) ** 2;
const branches: Spot[] = BRANDS.flatMap((b) =>
  Array.from({ length: Math.round(b.perKm2 * AREA_KM2) }, (_, i) => ({
    id: `b|${b.key}|${i}`,
    group: b.key,
    radius: BRANCH_RADIUS_M,
    ...at(rand() * SIZE_M, rand() * SIZE_M),
  })),
);
const places: Spot[] = Array.from({ length: 6 }, (_, i) => ({
  id: `p_${i}`,
  group: 'place',
  radius: 200,
  ...at(rand() * SIZE_M, rand() * SIZE_M),
}));

/** What a Kakao query around a grid cell would return for one brand: nearest 45 within 2.5km. */
function kakaoSearch(brand: string, here: Coords): Spot[] {
  const { center } = cellOf(here);
  return branches
    .filter((b) => b.group === brand)
    .map((b) => ({ b, d: distanceMeters(center, b) }))
    .filter((x) => x.d <= SEARCH_RADIUS_M)
    .sort((a, b) => a.d - b.d)
    .slice(0, KAKAO_CAP)
    .map((x) => x.b);
}

// ---- trips -------------------------------------------------------------------------------
type Trip = { points: Coords[]; meters: number };
const STEP_S = 10;

/** A trip through 3–5 random waypoints, walking (1.4 m/s) or by bus (6 m/s), sampled every 10s. */
function makeTrip(): Trip {
  const speed = rand() < 0.6 ? 1.4 : 6;
  const n = 3 + Math.floor(rand() * 3);
  const way = Array.from({ length: n }, () => ({ x: 1500 + rand() * (SIZE_M - 3000), y: 1500 + rand() * (SIZE_M - 3000) }));
  const points: Coords[] = [];
  let meters = 0;
  for (let w = 0; w < way.length - 1; w++) {
    const a = way[w];
    const b = way[w + 1];
    const len = Math.hypot(b.x - a.x, b.y - a.y);
    const steps = Math.max(1, Math.round(len / (speed * STEP_S)));
    for (let i = 0; i < steps; i++) points.push(at(a.x + ((b.x - a.x) * i) / steps, a.y + ((b.y - a.y) * i) / steps));
    meters += len;
  }
  return { points, meters };
}
const trips = Array.from({ length: 300 }, makeTrip);

/** Contiguous stretches inside each spot's radius: what the user would want an alert for. */
function visits(trip: Trip, spots: Spot[]): { spot: Spot; from: number; to: number }[] {
  const out: { spot: Spot; from: number; to: number }[] = [];
  for (const s of spots) {
    let start = -1;
    trip.points.forEach((p, t) => {
      const inside = distanceMeters(p, s) <= s.radius;
      if (inside && start < 0) start = t;
      if (!inside && start >= 0) {
        out.push({ spot: s, from: start, to: t - 1 });
        start = -1;
      }
    });
    if (start >= 0) out.push({ spot: s, from: start, to: trip.points.length - 1 });
  }
  return out;
}

// ---- strategies --------------------------------------------------------------------------
type Strategy = {
  name: string;
  slots: number;
  /** Re-plan when the user leaves a circle of this radius (null = never). */
  refreshM: number | null;
  /** Seconds between the exit and the new plan being active (background wake-up latency). */
  wakeDelayS: number;
};

type Result = { caught: number; total: number; replans: number; requests: number; km: number };

function run(st: Strategy): Result {
  let caught = 0;
  let total = 0;
  let replans = 0;
  let requests = 0;
  let km = 0;
  const cache = new Set<string>();
  const brands = BRANDS.map((b) => b.key);

  for (const trip of trips) {
    km += trip.meters / 1000;
    const plan = (here: Coords) => {
      const spots = [...places];
      for (const b of brands) {
        const key = `${b}|${cellOf(here).key}`;
        if (!cache.has(key)) {
          cache.add(key);
          requests++;
        }
        spots.push(...kakaoSearch(b, here));
      }
      const regions = planRegions({ here, spots, slots: st.slots, refreshRadius: st.refreshM ?? 1 }).regions;
      return {
        ids: new Set(regions.filter((r) => r.identifier !== REFRESH_ID).map((r) => r.identifier)),
        center: here,
      };
    };

    // registered[t] = set of region ids active at step t
    const registered: Set<string>[] = [];
    let current = plan(trip.points[0]);
    replans++;
    let pending: { at: number; from: Coords } | null = null;
    for (let t = 0; t < trip.points.length; t++) {
      const p = trip.points[t];
      if (pending && t >= pending.at) {
        current = plan(trip.points[t]);
        replans++;
        pending = null;
      }
      registered.push(current.ids);
      if (st.refreshM !== null && !pending && distanceMeters(current.center, p) > st.refreshM) {
        pending = { at: t + Math.ceil(st.wakeDelayS / STEP_S), from: p };
      }
    }

    for (const v of visits(trip, [...places, ...branches])) {
      total++;
      for (let t = v.from; t <= v.to; t++) {
        if (registered[t].has(v.spot.id)) {
          caught++;
          break;
        }
      }
    }
  }
  return { caught, total, replans, requests, km };
}

const strategies: Strategy[] = [
  { name: '고정 (출발 때 한 번, 90칸)', slots: 90, refreshM: null, wakeDelayS: 0 },
  ...[20, 50, 90].flatMap((slots) =>
    [500, 1000, 2000].map((r) => ({ name: `움직이는 창 ${slots}칸 · 갱신 ${r}m`, slots, refreshM: r, wakeDelayS: 120 })),
  ),
  { name: '움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 0초', slots: 90, refreshM: 1000, wakeDelayS: 0 },
  { name: '움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 5분', slots: 90, refreshM: 1000, wakeDelayS: 300 },
];

const rows = strategies.map((st) => ({ st, r: run(st) }));
const pct = (a: number, b: number) => `${((100 * a) / b).toFixed(1)}%`;
const table = [
  '| 전략 | 잡은 방문 | 다시 고른 횟수 / km | 카카오 요청 (전체) |',
  '|---|---|---|---|',
  ...rows.map(
    ({ st, r }) =>
      `| ${st.name} | ${pct(r.caught, r.total)} (${r.caught}/${r.total}) | ${(r.replans / r.km).toFixed(2)} | ${r.requests} |`,
  ),
].join('\n');

const summary = `## 지오펜스 전략 시뮬레이션

\`node scripts/simulate-window.ts\`로 다시 만들 수 있어요 (난수 시드 고정).

- 도시: ${SIZE_M / 1000}km × ${SIZE_M / 1000}km, 브랜드 3개 지점 ${branches.length}곳 (km²당 ${BRANDS.map((b) => b.perKm2).join(' / ')}곳), 저장한 장소 ${places.length}곳
- 이동: ${trips.length}번, 걷기 60% (1.4m/s) · 버스 40% (6m/s), 총 ${rows[0].r.km.toFixed(0)}km
- 방문: 지점 반경 ${BRANCH_RADIUS_M}m 안에 들어간 구간. 그 구간 중 한 순간이라도 그 지점이 OS에 등록돼 있으면 "잡은 방문"
- 카카오 검색은 실제처럼 격자 칸 중심에서 반경 ${SEARCH_RADIUS_M / 1000}km, 최대 ${KAKAO_CAP}곳만 돌려준다고 가정
- 별도 표시가 없으면 갱신 구역을 벗어난 뒤 앱이 깨어나 다시 고르기까지 2분 걸린다고 가정

${table}
`;

console.log(summary);
if (process.argv.includes('--write')) {
  writeFileSync(new URL('../docs/simulation.md', import.meta.url), summary);
  console.log('wrote docs/simulation.md');
}
