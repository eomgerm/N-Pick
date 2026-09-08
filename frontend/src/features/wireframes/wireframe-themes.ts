export const wireframeThemes = [
  {
    id: 'shinhan',
  },
] as const;

export type WireframeTheme = (typeof wireframeThemes)[number]['id'];

export function isWireframeTheme(value: string): value is WireframeTheme {
  return wireframeThemes.some(({ id }) => id === value);
}
