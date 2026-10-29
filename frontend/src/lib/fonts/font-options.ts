/**
 * Curated tenant font ids — mirrors the backend allowlist for
 * `header_font_family` / `body_font_family`
 * (docs/specs/tenant-appearance-public-home-parity.md §3). Pure data (no
 * `next/font` import) so validation and unit tests can use it; the actual
 * font loading lives in `public-fonts.ts`.
 */
export const APPEARANCE_FONT_IDS = [
  "INTER",
  "ROBOTO",
  "OPEN_SANS",
  "LATO",
  "POPPINS",
  "MONTSERRAT",
  "NOTO_SANS",
  "NOTO_SERIF",
  "GEIST",
  "SYSTEM",
  "SERIF",
] as const;

export type AppearanceFontId = (typeof APPEARANCE_FONT_IDS)[number];

export const DEFAULT_APPEARANCE_FONT: AppearanceFontId = "INTER";

export interface AppearanceFontOption {
  value: AppearanceFontId;
  label: string;
  serif: boolean;
  /** Older options kept so existing saved values stay valid. */
  legacy: boolean;
}

export const APPEARANCE_FONT_OPTIONS: readonly AppearanceFontOption[] = [
  { value: "INTER", label: "Inter", serif: false, legacy: false },
  { value: "ROBOTO", label: "Roboto", serif: false, legacy: false },
  { value: "OPEN_SANS", label: "Open Sans", serif: false, legacy: false },
  { value: "LATO", label: "Lato", serif: false, legacy: false },
  { value: "POPPINS", label: "Poppins", serif: false, legacy: false },
  { value: "MONTSERRAT", label: "Montserrat", serif: false, legacy: false },
  { value: "NOTO_SANS", label: "Noto Sans", serif: false, legacy: false },
  { value: "NOTO_SERIF", label: "Noto Serif", serif: true, legacy: false },
  { value: "GEIST", label: "Geist", serif: false, legacy: true },
  { value: "SYSTEM", label: "System UI", serif: false, legacy: true },
  { value: "SERIF", label: "System serif", serif: true, legacy: true },
];

export function isAppearanceFontId(value: unknown): value is AppearanceFontId {
  return typeof value === "string" && (APPEARANCE_FONT_IDS as readonly string[]).includes(value);
}

export function toAppearanceFontId(value: unknown): AppearanceFontId {
  return isAppearanceFontId(value) ? value : DEFAULT_APPEARANCE_FONT;
}
