/**
 * "Highlighter on a map": map paper and ink, park green for actions,
 * and highlighter yellow reserved for one meaning only — "you are near this place".
 */
export const color = {
  paper: '#F2F3EE',
  surface: '#FFFFFF',
  ink: '#1F2B2A',
  muted: '#6C7774',
  line: '#D9DED6',
  green: '#2D7A57',
  greenSoft: '#E3EFE7',
  highlight: '#FFE34D',
  amber: '#8A5A0A',
  amberSoft: '#FBF0D4',
} as const;

/** Embedded via the expo-font config plugin (app.json); family = file name. */
export const font = {
  display: 'BlackHanSans_400Regular',
  regular: 'IBMPlexSansKR_400Regular',
  medium: 'IBMPlexSansKR_500Medium',
  bold: 'IBMPlexSansKR_700Bold',
} as const;

/** Type scale on a 4px grid. */
export const type = {
  hero: { fontFamily: font.display, fontSize: 40, lineHeight: 46, color: color.ink },
  title: { fontFamily: font.bold, fontSize: 18, lineHeight: 26, color: color.ink },
  body: { fontFamily: font.regular, fontSize: 15, lineHeight: 22, color: color.ink },
  meta: { fontFamily: font.regular, fontSize: 13, lineHeight: 18, color: color.muted },
  label: { fontFamily: font.medium, fontSize: 13, lineHeight: 18, color: color.muted },
  distance: { fontFamily: font.display, fontSize: 22, lineHeight: 26, color: color.ink },
} as const;

export const space = { xs: 4, s: 8, m: 12, l: 16, xl: 24, xxl: 32 } as const;
