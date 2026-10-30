/**
 * Pure color helpers for tenant theming (no DOM, no React) — shared by the
 * Appearance settings form (live contrast feedback, presets) and
 * `PublicThemeScope` (derived tokens). WCAG 2.x relative-luminance math.
 *
 * Contrast here is UX feedback only: the backend independently rejects a
 * saved text/background pair below 4.5:1 or primary/background below 3:1
 * (docs/specs/tenant-appearance-public-home-parity.md §3).
 */

export const HEX_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/;

export function isHexColor(value: string | null | undefined): value is string {
  return typeof value === "string" && HEX_COLOR_PATTERN.test(value);
}

/** Label colors offered on top of primary/accent fills. */
export const LIGHT_LABEL = "#FFFFFF";
export const DARK_LABEL = "#0F172A";

export interface ThemeColors {
  primaryColor: string;
  accentColor: string;
  backgroundColor: string;
  surfaceColor: string;
  textColor: string;
}

export const DEFAULT_THEME_COLORS: ThemeColors = {
  primaryColor: "#1D4ED8",
  accentColor: "#F59E0B",
  backgroundColor: "#FFFFFF",
  surfaceColor: "#FFFFFF",
  textColor: "#0F172A",
};

export interface ThemePreset {
  id: string;
  label: string;
  colors: ThemeColors;
}

/**
 * Curated presets. Every pair is checked by `e2e/unit/appearance-theme.spec.ts`:
 * text vs background/surface >= 4.5, primary vs background >= 3, and the
 * auto-chosen label on primary/accent >= 4.5.
 */
export const THEME_PRESETS: ThemePreset[] = [
  {
    id: "ocean",
    label: "Ocean",
    colors: { primaryColor: "#1D4ED8", accentColor: "#0EA5E9", backgroundColor: "#FFFFFF", surfaceColor: "#F8FAFC", textColor: "#0F172A" },
  },
  {
    id: "forest",
    label: "Forest",
    colors: { primaryColor: "#15803D", accentColor: "#CA8A04", backgroundColor: "#F7FBF8", surfaceColor: "#FFFFFF", textColor: "#14281D" },
  },
  {
    id: "royal",
    label: "Royal",
    colors: { primaryColor: "#6D28D9", accentColor: "#BE185D", backgroundColor: "#FAF5FF", surfaceColor: "#FFFFFF", textColor: "#1E1B4B" },
  },
  {
    id: "sunset",
    label: "Sunset",
    colors: { primaryColor: "#C2410C", accentColor: "#F59E0B", backgroundColor: "#FFFBF5", surfaceColor: "#FFFFFF", textColor: "#292524" },
  },
  {
    id: "teal",
    label: "Teal",
    colors: { primaryColor: "#0F766E", accentColor: "#F59E0B", backgroundColor: "#F6FBFA", surfaceColor: "#FFFFFF", textColor: "#0F2A28" },
  },
  {
    id: "lagoon",
    label: "Lagoon",
    colors: { primaryColor: "#0369A1", accentColor: "#14B8A6", backgroundColor: "#F5FAFD", surfaceColor: "#FFFFFF", textColor: "#0C2333" },
  },
  {
    id: "indigo",
    label: "Indigo",
    colors: { primaryColor: "#4338CA", accentColor: "#10B981", backgroundColor: "#F8F9FF", surfaceColor: "#FFFFFF", textColor: "#1E1B4B" },
  },
  {
    id: "crimson",
    label: "Crimson",
    colors: { primaryColor: "#B91C1C", accentColor: "#D97706", backgroundColor: "#FFFAFA", surfaceColor: "#FFFFFF", textColor: "#1F1315" },
  },
  {
    id: "rose",
    label: "Rose",
    colors: { primaryColor: "#BE123C", accentColor: "#7C3AED", backgroundColor: "#FFF7F9", surfaceColor: "#FFFFFF", textColor: "#2A1520" },
  },
  {
    id: "slate",
    label: "Slate",
    colors: { primaryColor: "#334155", accentColor: "#0EA5E9", backgroundColor: "#F8FAFC", surfaceColor: "#FFFFFF", textColor: "#0F172A" },
  },
  {
    id: "charcoal-gold",
    label: "Charcoal & Gold",
    colors: { primaryColor: "#1F2937", accentColor: "#D4A017", backgroundColor: "#FAFAF7", surfaceColor: "#FFFFFF", textColor: "#111827" },
  },
  {
    id: "midnight",
    label: "Midnight (dark)",
    colors: { primaryColor: "#60A5FA", accentColor: "#FBBF24", backgroundColor: "#0B1220", surfaceColor: "#111A2E", textColor: "#E5E7EB" },
  },
];

function toRgb(hex: string): [number, number, number] {
  const value = hex.replace("#", "");
  return [0, 2, 4].map((offset) => parseInt(value.slice(offset, offset + 2), 16)) as [number, number, number];
}

function toHex([r, g, b]: [number, number, number]): string {
  return `#${[r, g, b]
    .map((channel) => Math.round(Math.min(255, Math.max(0, channel))).toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase()}`;
}

function linearize(channel: number): number {
  const c = channel / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

export function relativeLuminance(hex: string): number {
  const [r, g, b] = toRgb(hex).map(linearize);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio between two `#RRGGBB` colors (1–21). */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
}

/** White or slate-900, whichever contrasts more with `fill`. */
export function readableLabelOn(fill: string): string {
  return contrastRatio(fill, LIGHT_LABEL) >= contrastRatio(fill, DARK_LABEL) ? LIGHT_LABEL : DARK_LABEL;
}

/** Linear sRGB-space mix: `amountOfB` = 0 returns `a`, 1 returns `b`. */
export function mixHex(a: string, b: string, amountOfB: number): string {
  const ca = toRgb(a);
  const cb = toRgb(b);
  return toHex([0, 1, 2].map((i) => ca[i] + (cb[i] - ca[i]) * amountOfB) as [number, number, number]);
}

/**
 * A softer secondary-text color: the text color blended toward the
 * background as far as possible while staying >= 4.5:1 on the page
 * background and every other given surface (card, muted band). Falls back to the text color itself.
 */
export function deriveMutedForeground(text: string, background: string, ...otherBackgrounds: string[]): string {
  const surfaces = [background, ...otherBackgrounds];
  for (let amount = 0.45; amount > 0; amount -= 0.05) {
    const candidate = mixHex(text, background, amount);
    if (surfaces.every((surface) => contrastRatio(candidate, surface) >= 4.5)) return candidate;
  }
  return text;
}

/** Resolves possibly-empty/invalid values to the documented defaults. */
export function resolveThemeColors(partial: Partial<Record<keyof ThemeColors, string | null | undefined>> | null | undefined): ThemeColors {
  const pick = (key: keyof ThemeColors) => {
    const value = partial?.[key];
    return isHexColor(value) ? value.toUpperCase() : DEFAULT_THEME_COLORS[key];
  };
  return {
    primaryColor: pick("primaryColor"),
    accentColor: pick("accentColor"),
    backgroundColor: pick("backgroundColor"),
    surfaceColor: pick("surfaceColor"),
    textColor: pick("textColor"),
  };
}

export interface ContrastReport {
  textOnBackground: number;
  textOnSurface: number;
  primaryOnBackground: number;
  labelOnPrimary: number;
  labelOnAccent: number;
}

export function contrastReport(colors: ThemeColors): ContrastReport {
  return {
    textOnBackground: contrastRatio(colors.textColor, colors.backgroundColor),
    textOnSurface: contrastRatio(colors.textColor, colors.surfaceColor),
    primaryOnBackground: contrastRatio(colors.primaryColor, colors.backgroundColor),
    labelOnPrimary: contrastRatio(readableLabelOn(colors.primaryColor), colors.primaryColor),
    labelOnAccent: contrastRatio(readableLabelOn(colors.accentColor), colors.accentColor),
  };
}

/** The full CSS custom-property map applied on `.tenant-public-theme`. */
export function themeCssVariables(colors: ThemeColors): Record<string, string> {
  const { primaryColor, accentColor, backgroundColor, surfaceColor, textColor } = colors;
  const muted = mixHex(backgroundColor, textColor, 0.05);
  const border = mixHex(backgroundColor, textColor, 0.14);
  return {
    "--primary": primaryColor,
    "--primary-foreground": readableLabelOn(primaryColor),
    "--ring": primaryColor,
    "--background": backgroundColor,
    "--foreground": textColor,
    "--card": surfaceColor,
    "--card-foreground": textColor,
    "--popover": surfaceColor,
    "--popover-foreground": textColor,
    "--muted": muted,
    "--muted-foreground": deriveMutedForeground(textColor, backgroundColor, surfaceColor, muted),
    "--secondary": muted,
    "--secondary-foreground": textColor,
    "--accent": mixHex(backgroundColor, textColor, 0.07),
    "--accent-foreground": textColor,
    "--border": border,
    "--input": border,
    "--tenant-accent": accentColor,
    "--tenant-accent-foreground": readableLabelOn(accentColor),
  };
}

/**
 * Dark-mode tenant palette (LOGIN-01 Phase A): the tenant's primary/accent
 * are kept as brand colours, but the custom background/surface/text are
 * replaced by dark tokens (a light-mode background chosen by the tenant can't
 * work in dark mode). Primary and accent are lightened in steps until they
 * reach 3:1 against the dark background, so buttons and links stay visible.
 */
export const DARK_THEME_BASE = {
  backgroundColor: "#0B1120",
  surfaceColor: "#111827",
  textColor: "#E5E7EB",
} as const;

function liftForDarkBackground(color: string, background: string): string {
  let candidate = color;
  for (let step = 1; step <= 8 && contrastRatio(candidate, background) < 3; step += 1) {
    candidate = mixHex(color, "#FFFFFF", step * 0.1);
  }
  return candidate;
}

export function darkThemeColors(colors: ThemeColors): ThemeColors {
  return {
    primaryColor: liftForDarkBackground(colors.primaryColor, DARK_THEME_BASE.backgroundColor),
    accentColor: liftForDarkBackground(colors.accentColor, DARK_THEME_BASE.backgroundColor),
    ...DARK_THEME_BASE,
  };
}

/**
 * Inline variables for `.tenant-public-theme`: the light set as `--tl-*` and
 * the dark set as `--td-*`. `globals.css` maps them onto the real tokens
 * (`--primary`, `--background`, …) with `.dark` choosing the dark set, so the
 * correct palette applies before first paint with no client re-render.
 */
export function themeScopeCssVariables(colors: ThemeColors): Record<string, string> {
  const light = themeCssVariables(colors);
  const dark = themeCssVariables(darkThemeColors(colors));
  const scoped: Record<string, string> = {};
  for (const [name, value] of Object.entries(light)) scoped[`--tl-${name.slice(2)}`] = value;
  for (const [name, value] of Object.entries(dark)) scoped[`--td-${name.slice(2)}`] = value;
  return scoped;
}
