/**
 * The map page that runs inside a WebView: Leaflet + OpenStreetMap raster tiles (no API key, no WebGL,
 * so it renders on any Android WebView including emulators), softened toward the app palette.
 * OSM's public tiles are fine for a prototype; a released app should move to a paid/own tile server.
 * React Native drives it through window.snap.* and listens for {type:'ready'|'select'|'tapMap'|'error'}.
 */
import { color } from '../ui/theme';

const LEAFLET = 'https://unpkg.com/leaflet@1.9.4/dist';
const TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

export const mapHtml = `<!doctype html>
<html><head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
<link rel="stylesheet" href="${LEAFLET}/leaflet.css" />
<script src="${LEAFLET}/leaflet.js"></script>
<style>
  html, body, #map { margin: 0; height: 100%; background: ${color.paper}; }
  .leaflet-container { background: ${color.paper}; font-family: sans-serif; }
  /* Pull the stock basemap toward map paper and park green. */
  .leaflet-tile-pane { filter: saturate(.45) sepia(.1) hue-rotate(40deg) brightness(1.05) contrast(.92); }
  .leaflet-top.leaflet-right { top: 104px; }
  .leaflet-control-attribution { font-size: 10px; background: rgba(242,243,238,.85) !important; }
  .pin { position: relative; width: 52px; height: 66px; transition: transform .18s ease; transform-origin: 50% 100%; }
  .pin .frame { position: absolute; left: 0; top: 0; width: 46px; height: 58px; border-radius: 9px; overflow: hidden;
    background: ${color.greenSoft}; border: 3px solid ${color.surface};
    box-shadow: 0 1px 0 ${color.line}, 0 6px 14px rgba(31,43,42,.22); }
  /* Leaflet forces width:auto on marker images; pin thumbnails must fill their frame. */
  .leaflet-marker-pane .pin img { width: 100% !important; height: 100% !important; object-fit: cover; object-position: top; display: block; }
  .pin .tail { position: absolute; left: 20px; top: 54px; width: 12px; height: 12px;
    background: ${color.surface}; transform: rotate(45deg); border-radius: 2px; }
  .pin.near .frame { border-color: ${color.highlight}; }
  .pin.near .tail { background: ${color.highlight}; }
  .pin.selected { transform: scale(1.18); }
  .pin.selected .frame { border-color: ${color.ink}; }
  .pin.selected .tail { background: ${color.ink}; }
  .br { width: 26px; height: 26px; border-radius: 50%; background: ${color.surface}; border: 2px solid ${color.ink};
    display: flex; align-items: center; justify-content: center; font: 700 12px sans-serif; color: ${color.ink};
    box-shadow: 0 2px 6px rgba(31,43,42,.2); box-sizing: border-box; }
  .br.near { background: ${color.highlight}; }
  .me { width: 16px; height: 16px; border-radius: 50%; background: ${color.green}; border: 3px solid #fff;
    box-shadow: 0 0 0 6px rgba(45,122,87,.2); box-sizing: border-box; }
</style>
</head><body><div id="map"></div>
<script>
const post = (m) => window.ReactNativeWebView && window.ReactNativeWebView.postMessage(JSON.stringify(m));
window.onerror = (msg, src, line) => post({ type: 'error', message: msg + ' @' + line });

const map = L.map('map', { zoomControl: false, attributionControl: true }).setView([37.5665, 126.978], 11);
map.attributionControl.setPrefix(false).setPosition('topright');
L.tileLayer('${TILES}', {
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap',
}).addTo(map);
map.on('click', () => post({ type: 'tapMap' }));

let markers = {}, circles = {}, brs = {}, me = null, refresh = null,
  state = { places: [], branches: [], refresh: null, here: null, selectedId: null };

function pinHtml() { return '<div class="pin"><div class="tail"></div><div class="frame"><img alt="" /></div></div>'; }

function render(fit) {
  const { places, here, selectedId } = state;
  const keep = new Set(places.map((p) => p.id));
  for (const id of Object.keys(markers)) if (!keep.has(id)) { markers[id].remove(); circles[id].remove(); delete markers[id]; delete circles[id]; }

  for (const p of places) {
    const ll = [p.lat, p.lng];
    let m = markers[p.id];
    if (!m) {
      m = markers[p.id] = L.marker(ll, {
        icon: L.divIcon({ className: '', html: pinHtml(), iconSize: [52, 66], iconAnchor: [26, 66] }),
        keyboard: false, title: p.name,
      }).addTo(map);
      m.on('click', (e) => { L.DomEvent.stopPropagation(e); post({ type: 'select', id: p.id }); });
      circles[p.id] = L.circle(ll, { radius: p.radius, interactive: false }).addTo(map);
    }
    m.setLatLng(ll);
    m.setZIndexOffset(p.id === selectedId ? 1000 : p.near ? 500 : 0);
    circles[p.id].setLatLng(ll).setRadius(p.radius).setStyle(p.near
      ? { color: '#C9A800', weight: 1.5, dashArray: '4 4', fillColor: '${color.highlight}', fillOpacity: 0.35 }
      : { color: '${color.green}', weight: 1.5, dashArray: '4 4', opacity: 0.6, fillColor: '${color.green}', fillOpacity: 0.08 });
    const el = m.getElement() && m.getElement().querySelector('.pin');
    if (el) {
      el.classList.toggle('near', !!p.near);
      el.classList.toggle('selected', p.id === selectedId);
      const img = el.querySelector('img');
      if (p.thumb && img.getAttribute('src') !== p.thumb) img.setAttribute('src', p.thumb);
    }
  }

  // Coupon branches currently in the geofence window: small badges with the brand's first letter.
  const branchIds = new Set((state.branches || []).map((b) => b.id));
  for (const id of Object.keys(brs)) if (!branchIds.has(id)) { brs[id].remove(); delete brs[id]; }
  for (const b of state.branches || []) {
    if (!brs[b.id]) {
      brs[b.id] = L.marker([b.lat, b.lng], {
        icon: L.divIcon({ className: '', html: '<div class="br"></div>', iconSize: [26, 26], iconAnchor: [13, 13] }),
        interactive: false, keyboard: false, title: b.name,
      }).addTo(map);
    }
    const el = brs[b.id].getElement() && brs[b.id].getElement().querySelector('.br');
    if (el) { el.textContent = b.label; el.classList.toggle('near', !!b.near); }
  }

  // The refresh region: leaving it makes the app pick nearby spots again.
  const r = state.refresh;
  if (r) {
    if (!refresh) refresh = L.circle([r.lat, r.lng], { radius: r.radius, interactive: false,
      color: '${color.ink}', weight: 2, opacity: 0.55, dashArray: '8 8', fill: false }).addTo(map);
    refresh.setLatLng([r.lat, r.lng]).setRadius(r.radius);
  } else if (refresh) { refresh.remove(); refresh = null; }

  if (here) {
    const ll = [here.lat, here.lng];
    if (!me) me = L.marker(ll, { icon: L.divIcon({ className: '', html: '<div class="me"></div>', iconSize: [16, 16], iconAnchor: [8, 8] }), interactive: false, keyboard: false }).addTo(map);
    else me.setLatLng(ll);
  }

  if (fit) {
    const pts = places.map((p) => [p.lat, p.lng]).concat(here ? [[here.lat, here.lng]] : []);
    if (pts.length === 1) map.setView(pts[0], 15);
    else if (pts.length > 1) map.fitBounds(pts, { paddingTopLeft: [50, 130], paddingBottomRight: [50, 200], maxZoom: 15 });
  }
}

window.snap = {
  setData(next, fit) { state = next; render(!!fit); },
  focus(lng, lat) {
    const z = Math.max(map.getZoom(), 15);
    // Aim a little above the center so the pin is not hidden behind the card rail.
    const target = map.unproject(map.project([lat, lng], z).add([0, 70]), z);
    map.flyTo(target, z, { duration: 0.6 });
  },
};
post({ type: 'ready' });
</script></body></html>`;
