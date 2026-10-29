import {
  Inter,
  Lato,
  Montserrat,
  Noto_Sans,
  Noto_Sans_Sinhala,
  Noto_Sans_Tamil,
  Noto_Serif,
  Open_Sans,
  Poppins,
  Roboto,
} from "next/font/google";
import { APPEARANCE_FONT_OPTIONS, toAppearanceFontId, type AppearanceFontId } from "./font-options";

/**
 * Predeclared, self-hosted tenant font registry (spec §4 "Fonts"). `next/font`
 * downloads every family at build time and serves it from our own origin — no
 * runtime request to Google, so no CSP change, and arbitrary families are
 * impossible by design.
 *
 * `preload: false` + applying a family's `variable` class only where it is
 * referenced (the `.tenant-public-theme` wrapper for the active tenant's two
 * fonts, and the settings font picker) means a browser downloads only the
 * families actually rendered. Sinhala/Tamil Noto families are declared with
 * their own script subsets and sit after the chosen Latin font in every stack,
 * so Sinhala/Tamil names render without claiming the Latin font supports them.
 */

const inter = Inter({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-inter" });
const roboto = Roboto({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-roboto" });
const openSans = Open_Sans({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-open-sans" });
const lato = Lato({ subsets: ["latin"], display: "swap", preload: false, weight: ["400", "700"], variable: "--font-lato" });
const poppins = Poppins({ subsets: ["latin"], display: "swap", preload: false, weight: ["400", "500", "600", "700"], variable: "--font-poppins" });
const montserrat = Montserrat({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-montserrat" });
const notoSans = Noto_Sans({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-noto-sans" });
const notoSerif = Noto_Serif({ subsets: ["latin"], display: "swap", preload: false, variable: "--font-noto-serif" });
const notoSinhala = Noto_Sans_Sinhala({ subsets: ["sinhala"], display: "swap", preload: false, variable: "--font-noto-sinhala" });
const notoTamil = Noto_Sans_Tamil({ subsets: ["tamil"], display: "swap", preload: false, variable: "--font-noto-tamil" });

const SCRIPT_FALLBACKS = "var(--font-noto-sinhala), var(--font-noto-tamil)";
const SANS_TAIL = `${SCRIPT_FALLBACKS}, ui-sans-serif, system-ui, sans-serif`;
const SERIF_TAIL = `${SCRIPT_FALLBACKS}, ui-serif, Georgia, Cambria, "Times New Roman", serif`;

/** Always applied with any tenant font so the Sinhala/Tamil fallbacks resolve. */
export const SCRIPT_FALLBACK_FONT_CLASSES = `${notoSinhala.variable} ${notoTamil.variable}`;

export interface PublicFontEntry {
  id: AppearanceFontId;
  label: string;
  /** `next/font` class that declares this family's CSS variable ("" for system/legacy stacks). */
  variableClass: string;
  /** Full `font-family` value, ending with Sinhala/Tamil and generic fallbacks. */
  stack: string;
}

function entry(id: AppearanceFontId, variableClass: string, family: string): PublicFontEntry {
  const option = APPEARANCE_FONT_OPTIONS.find((candidate) => candidate.value === id);
  const tail = option?.serif ? SERIF_TAIL : SANS_TAIL;
  return { id, label: option?.label ?? id, variableClass, stack: family ? `${family}, ${tail}` : tail };
}

export const PUBLIC_FONTS: Record<AppearanceFontId, PublicFontEntry> = {
  INTER: entry("INTER", inter.variable, "var(--font-inter)"),
  ROBOTO: entry("ROBOTO", roboto.variable, "var(--font-roboto)"),
  OPEN_SANS: entry("OPEN_SANS", openSans.variable, "var(--font-open-sans)"),
  LATO: entry("LATO", lato.variable, "var(--font-lato)"),
  POPPINS: entry("POPPINS", poppins.variable, "var(--font-poppins)"),
  MONTSERRAT: entry("MONTSERRAT", montserrat.variable, "var(--font-montserrat)"),
  NOTO_SANS: entry("NOTO_SANS", notoSans.variable, "var(--font-noto-sans)"),
  NOTO_SERIF: entry("NOTO_SERIF", notoSerif.variable, "var(--font-noto-serif)"),
  // Geist's variable is already declared on <html> by the root layout.
  GEIST: entry("GEIST", "", "var(--font-geist-sans)"),
  SYSTEM: entry("SYSTEM", "", ""),
  SERIF: entry("SERIF", "", ""),
};

export function publicFont(value: unknown): PublicFontEntry {
  return PUBLIC_FONTS[toAppearanceFontId(value)];
}
