import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { distanceMeters, type Coords } from '../geo';
import type { WindowState } from '../storage';
import type { Place } from '../types';
import { color } from '../ui/theme';
import { mapHtml } from './mapHtml';

export type SpotMapHandle = { focus: (p: Place) => void };

/** Small data-URI thumbnails of each place's screenshot, for the map pins. Cached per image. */
function useThumbs(places: Place[]): Record<string, string> {
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const pending = useRef(new Set<string>());
  useEffect(() => {
    for (const p of places) {
      const uri = p.imageUri;
      if (!uri || thumbs[uri] || pending.current.has(uri)) continue;
      pending.current.add(uri);
      (async () => {
        try {
          const ctx = ImageManipulator.manipulate(uri);
          ctx.resize({ width: 120 });
          const img = await (await ctx.renderAsync()).saveAsync({ format: SaveFormat.JPEG, base64: true, compress: 0.7 });
          if (img.base64) setThumbs((t) => ({ ...t, [uri]: `data:image/jpeg;base64,${img.base64}` }));
        } catch {
          // The screenshot may have been cleared from cache; the pin falls back to a plain frame.
        }
      })();
    }
  }, [places, thumbs]);
  return thumbs;
}

export const SpotMap = forwardRef<
  SpotMapHandle,
  {
    places: Place[];
    here: Coords | null;
    selectedId: string | null;
    onSelect: (id: string | null) => void;
    /** The registered geofence plan: coupon branches and the refresh region. */
    win: WindowState | null;
    branchRadius: number;
    refreshRadius: number;
  }
>(function SpotMap({ places, here, selectedId, onSelect, win, branchRadius, refreshRadius }, ref) {
  const web = useRef<WebView>(null);
  const [ready, setReady] = useState(false);
  const fitted = useRef(false);
  const thumbs = useThumbs(places);

  useImperativeHandle(ref, () => ({
    focus: (p) => web.current?.injectJavaScript(`window.snap.focus(${p.longitude}, ${p.latitude}); true;`),
  }));

  useEffect(() => {
    if (!ready) return;
    const data = {
      places: places.map((p) => ({
        id: p.id,
        name: p.name,
        lat: p.latitude,
        lng: p.longitude,
        radius: p.radius,
        near: here ? distanceMeters(here, p) <= p.radius : false,
        thumb: p.imageUri ? (thumbs[p.imageUri] ?? null) : null,
      })),
      here: here ? { lat: here.latitude, lng: here.longitude } : null,
      selectedId,
      branches: Object.entries(win?.branches ?? {}).map(([id, b]) => ({
        id,
        name: b.name,
        label: b.name.trim().charAt(0),
        lat: b.latitude,
        lng: b.longitude,
        near: here ? distanceMeters(here, b) <= branchRadius : false,
      })),
      refresh: win?.here ? { lat: win.here.latitude, lng: win.here.longitude, radius: refreshRadius } : null,
    };
    // Fit the camera once, when there is something to show; after that the user owns the camera.
    const fit = !fitted.current && (places.length > 0 || !!here);
    if (fit) fitted.current = true;
    web.current?.injectJavaScript(`window.snap.setData(${JSON.stringify(data)}, ${fit}); true;`);
  }, [ready, places, here, selectedId, thumbs, win, branchRadius, refreshRadius]);

  const onMessage = (e: WebViewMessageEvent) => {
    const msg = JSON.parse(e.nativeEvent.data) as { type: string; id?: string; message?: string };
    if (msg.type === 'ready') setReady(true);
    else if (msg.type === 'select' && msg.id) onSelect(msg.id);
    else if (msg.type === 'tapMap') onSelect(null);
    else if (msg.type === 'error') console.warn('map error', msg.message);
  };

  return (
    <View style={s.fill}>
      <WebView
        ref={web}
        source={{ html: mapHtml, baseUrl: 'https://snapspot.local/' }}
        originWhitelist={['*']}
        onMessage={onMessage}
        style={s.fill}
        containerStyle={{ backgroundColor: color.paper }}
        overScrollMode="never"
        setBuiltInZoomControls={false}
        accessibilityLabel="저장한 장소 지도"
      />
    </View>
  );
});

const s = StyleSheet.create({ fill: { flex: 1, backgroundColor: color.paper } });
