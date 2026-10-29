import { z } from "zod";
import { APPEARANCE_FONT_IDS, toAppearanceFontId } from "@/lib/fonts/font-options";
import { contrastRatio, DEFAULT_THEME_COLORS, HEX_COLOR_PATTERN, resolveThemeColors, type ThemeColors } from "@/lib/theme/color";

/**
 * Appearance settings form (BRANDING domain keys owned by
 * `/tenant-admin/settings/appearance`). Client validation is UX only — the
 * backend re-validates hex format, the font/size allowlists, URL schemes and
 * the contrast minimums (docs/specs/tenant-appearance-public-home-parity.md §3).
 */

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && Boolean(url.hostname);
  } catch {
    return false;
  }
}

const YOUTUBE_HOSTS = ["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"];

/** Extracts a YouTube video id from a watch / youtu.be URL, or null. */
export function youTubeVideoId(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || !YOUTUBE_HOSTS.includes(url.hostname)) return null;
    const id = url.hostname === "youtu.be" ? url.pathname.slice(1) : url.searchParams.get("v");
    return id && /^[A-Za-z0-9_-]{6,20}$/.test(id) ? id : null;
  } catch {
    return null;
  }
}

/** Privacy-enhanced embed URL for a configured support video, or null. */
export function safeYouTubeEmbedUrl(value: string | null | undefined): string | null {
  const id = youTubeVideoId(value);
  return id ? `https://www.youtube-nocookie.com/embed/${id}` : null;
}

const optionalHex = z.string().refine((value) => value === "" || HEX_COLOR_PATTERN.test(value), "Enter a six-digit hex color, e.g. #1D4ED8.");

export const COLOR_FIELDS = ["primary_color", "accent_color", "background_color", "surface_color", "text_color"] as const;
export type ColorField = (typeof COLOR_FIELDS)[number];

export const COLOR_FIELD_DEFAULTS: Record<ColorField, string> = {
  primary_color: DEFAULT_THEME_COLORS.primaryColor,
  accent_color: DEFAULT_THEME_COLORS.accentColor,
  background_color: DEFAULT_THEME_COLORS.backgroundColor,
  surface_color: DEFAULT_THEME_COLORS.surfaceColor,
  text_color: DEFAULT_THEME_COLORS.textColor,
};

export const MIN_TEXT_CONTRAST = 4.5;
export const MIN_PRIMARY_CONTRAST = 3;

export const appearanceConfigSchema = z
  .object({
    homepage_url: z.string().trim().refine((value) => value === "" || isHttpUrl(value), "Enter a valid http(s) URL."),
    public_page_enabled: z.boolean(),
    sub_hero_enabled: z.boolean(),
    cta_enabled: z.boolean(),
    teacher_auto_rotate: z.boolean(),
    featured_course_ids: z.array(z.string().min(1)).max(24, "Choose at most 24 featured courses."),
    support_video_url: z
      .string()
      .trim()
      .refine((value) => value === "" || youTubeVideoId(value) !== null, "Enter a YouTube link such as https://www.youtube.com/watch?v=… or https://youtu.be/…"),
    logo_display_size: z.enum(["SMALL", "STANDARD", "LARGE"]),
    header_font_family: z.enum(APPEARANCE_FONT_IDS),
    body_font_family: z.enum(APPEARANCE_FONT_IDS),
    primary_color: optionalHex,
    accent_color: optionalHex,
    background_color: optionalHex,
    surface_color: optionalHex,
    text_color: optionalHex,
  })
  .superRefine((values, ctx) => {
    if (!COLOR_FIELDS.every((field) => values[field] === "" || HEX_COLOR_PATTERN.test(values[field]))) return;
    const colors = themeColorsFromForm(values);
    const textOnBg = contrastRatio(colors.textColor, colors.backgroundColor);
    const textOnSurface = contrastRatio(colors.textColor, colors.surfaceColor);
    const primaryOnBg = contrastRatio(colors.primaryColor, colors.backgroundColor);
    if (textOnBg < MIN_TEXT_CONTRAST) {
      ctx.addIssue({ code: "custom", path: ["text_color"], message: `Main text needs at least 4.5:1 contrast with the page background (currently ${textOnBg.toFixed(2)}:1).` });
    } else if (textOnSurface < MIN_TEXT_CONTRAST) {
      ctx.addIssue({ code: "custom", path: ["text_color"], message: `Main text needs at least 4.5:1 contrast with the surface color (currently ${textOnSurface.toFixed(2)}:1).` });
    }
    if (primaryOnBg < MIN_PRIMARY_CONTRAST) {
      ctx.addIssue({ code: "custom", path: ["primary_color"], message: `Primary needs at least 3:1 contrast with the page background (currently ${primaryOnBg.toFixed(2)}:1).` });
    }
  });

export type AppearanceConfigFormValues = z.infer<typeof appearanceConfigSchema>;

export const APPEARANCE_DEFAULT_VALUES: AppearanceConfigFormValues = {
  homepage_url: "",
  public_page_enabled: true,
  sub_hero_enabled: true,
  cta_enabled: true,
  teacher_auto_rotate: false,
  featured_course_ids: [],
  support_video_url: "",
  logo_display_size: "STANDARD",
  header_font_family: "INTER",
  body_font_family: "INTER",
  primary_color: "",
  accent_color: "",
  background_color: "",
  surface_color: "",
  text_color: "",
};

/** Keys this page owns. Never sends `secondary_color` or legacy banner URL keys. */
export const APPEARANCE_KEYS = Object.keys(APPEARANCE_DEFAULT_VALUES) as (keyof AppearanceConfigFormValues)[];

export function themeColorsFromForm(values: Pick<AppearanceConfigFormValues, ColorField>): ThemeColors {
  return resolveThemeColors({
    primaryColor: values.primary_color,
    accentColor: values.accent_color,
    backgroundColor: values.background_color,
    surfaceColor: values.surface_color,
    textColor: values.text_color,
  });
}

function asString(value: unknown): string {
  return value == null ? "" : String(value);
}

/** Builds form values from the raw BRANDING property rows (`{ key, value }`). */
export function appearanceFormValues(properties: { key: string; value: unknown }[]): AppearanceConfigFormValues {
  const values: AppearanceConfigFormValues = { ...APPEARANCE_DEFAULT_VALUES, featured_course_ids: [] };
  for (const { key, value } of properties) {
    switch (key) {
      case "homepage_url":
      case "support_video_url":
        values[key] = asString(value);
        break;
      case "primary_color":
      case "accent_color":
      case "background_color":
      case "surface_color":
      case "text_color":
        values[key] = asString(value).toUpperCase();
        break;
      case "public_page_enabled":
      case "sub_hero_enabled":
      case "cta_enabled":
        values[key] = value !== false;
        break;
      case "teacher_auto_rotate":
        values.teacher_auto_rotate = value === true;
        break;
      case "featured_course_ids":
        values.featured_course_ids = Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string") : [];
        break;
      case "logo_display_size":
        values.logo_display_size = value === "SMALL" || value === "LARGE" ? value : "STANDARD";
        break;
      case "header_font_family":
      case "body_font_family":
        values[key] = toAppearanceFontId(value);
        break;
      default:
        break;
    }
  }
  return values;
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/**
 * The PUT body: only keys whose value changed. If any color changed, all five
 * colors are sent together so the server's same-batch contrast validation
 * always sees the full pair set. Empty strings are sent as `null` (= default).
 */
export function toAppearanceChanges(initial: AppearanceConfigFormValues, values: AppearanceConfigFormValues): Record<string, unknown> {
  const changes: Record<string, unknown> = {};
  for (const key of APPEARANCE_KEYS) {
    if (COLOR_FIELDS.includes(key as ColorField)) continue;
    const before = initial[key];
    const after = values[key];
    if (JSON.stringify(before) === JSON.stringify(after)) continue;
    changes[key] = typeof after === "string" && (key === "homepage_url" || key === "support_video_url") ? emptyToNull(after) : after;
  }
  if (COLOR_FIELDS.some((field) => initial[field].toUpperCase() !== values[field].toUpperCase())) {
    for (const field of COLOR_FIELDS) changes[field] = emptyToNull(values[field].toUpperCase());
  }
  return changes;
}
