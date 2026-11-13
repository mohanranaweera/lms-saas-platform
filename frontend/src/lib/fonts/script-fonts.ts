import { Noto_Sans_Sinhala, Noto_Sans_Tamil } from "next/font/google";

/**
 * Sinhala and Tamil script fonts (I18N-01). Self-hosted by `next/font` (no
 * runtime request to Google). Declared once here and applied on <html> by the
 * root layout so every surface - public site, auth pages and portals - can
 * fall back to them; each face carries its script's `unicode-range`, so a
 * browser downloads one only when Sinhala/Tamil text is actually rendered.
 */
const notoSinhala = Noto_Sans_Sinhala({ subsets: ["sinhala"], display: "swap", preload: false, variable: "--font-noto-sinhala" });
const notoTamil = Noto_Sans_Tamil({ subsets: ["tamil"], display: "swap", preload: false, variable: "--font-noto-tamil" });

/** Classes declaring `--font-noto-sinhala` / `--font-noto-tamil`. */
export const SCRIPT_FONT_VARIABLE_CLASSES = `${notoSinhala.variable} ${notoTamil.variable}`;
