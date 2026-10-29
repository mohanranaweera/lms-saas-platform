import { test, expect } from "@playwright/test";
import {
  contrastRatio,
  deriveMutedForeground,
  readableLabelOn,
  resolveThemeColors,
  THEME_PRESETS,
  themeCssVariables,
  DEFAULT_THEME_COLORS,
} from "../../src/lib/theme/color";
import {
  APPEARANCE_DEFAULT_VALUES,
  appearanceConfigSchema,
  appearanceFormValues,
  toAppearanceChanges,
  youTubeVideoId,
  type AppearanceConfigFormValues,
} from "../../src/lib/validation/appearance";
import { analyzeContentBounds, evaluateImage, sniffImageType } from "../../src/lib/branding/image-checks";

/**
 * Unit tests (no browser, no server) for tenant appearance: WCAG contrast
 * math, curated presets, the Appearance form schema and PUT-body diff, and
 * the pre-upload image checks. All of this is UX only — the backend
 * re-validates every value.
 */

function issuesOf(values: AppearanceConfigFormValues): string[] {
  const result = appearanceConfigSchema.safeParse(values);
  return result.success ? [] : result.error.issues.map((issue) => issue.path.join("."));
}

test.describe("contrast math and presets", () => {
  test("contrast ratio matches WCAG reference values", () => {
    expect(contrastRatio("#FFFFFF", "#000000")).toBeCloseTo(21, 5);
    expect(contrastRatio("#FFFFFF", "#FFFFFF")).toBeCloseTo(1, 5);
    // blue-700 on white is a well-known ~6.7:1.
    expect(contrastRatio("#1D4ED8", "#FFFFFF")).toBeGreaterThan(6.5);
  });

  test("button labels auto-pick white or slate-900", () => {
    expect(readableLabelOn("#1D4ED8")).toBe("#FFFFFF");
    expect(readableLabelOn("#F59E0B")).toBe("#0F172A");
    expect(readableLabelOn("#FDE68A")).toBe("#0F172A");
  });

  test("every preset meets AA for text, primary and button labels", () => {
    expect(THEME_PRESETS.length).toBe(12);
    expect(new Set(THEME_PRESETS.map((p) => p.id)).size).toBe(THEME_PRESETS.length);
    for (const preset of THEME_PRESETS) {
      const c = preset.colors;
      expect(contrastRatio(c.textColor, c.backgroundColor), `${preset.id} text/bg`).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(c.textColor, c.surfaceColor), `${preset.id} text/surface`).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(c.primaryColor, c.backgroundColor), `${preset.id} primary/bg`).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(readableLabelOn(c.primaryColor), c.primaryColor), `${preset.id} label/primary`).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(readableLabelOn(c.accentColor), c.accentColor), `${preset.id} label/accent`).toBeGreaterThanOrEqual(4.5);
    }
  });

  test("derived muted text stays readable on background, surface and muted band", () => {
    for (const preset of [...THEME_PRESETS.map((p) => p.colors), DEFAULT_THEME_COLORS]) {
      const vars = themeCssVariables(preset);
      for (const surface of [vars["--background"], vars["--card"], vars["--muted"]]) {
        expect(contrastRatio(vars["--muted-foreground"], surface)).toBeGreaterThanOrEqual(4.5);
      }
    }
    expect(deriveMutedForeground("#0F172A", "#FFFFFF")).not.toBe("#0F172A");
  });

  test("null or invalid theme values fall back to the documented defaults", () => {
    expect(resolveThemeColors(null)).toEqual(DEFAULT_THEME_COLORS);
    expect(resolveThemeColors({ primaryColor: "red", textColor: "#abcdef" })).toEqual({
      ...DEFAULT_THEME_COLORS,
      textColor: "#ABCDEF",
    });
    const vars = themeCssVariables(DEFAULT_THEME_COLORS);
    expect(vars["--primary"]).toBe("#1D4ED8");
    expect(vars["--tenant-accent"]).toBe("#F59E0B");
    expect(vars).not.toHaveProperty("--destructive");
  });
});

test.describe("appearance form schema", () => {
  test("defaults (all colors empty) are valid", () => {
    expect(issuesOf(APPEARANCE_DEFAULT_VALUES)).toEqual([]);
  });

  test("rejects malformed hex and non-YouTube support URLs", () => {
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, primary_color: "#12345" })).toContain("primary_color");
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, accent_color: "blue" })).toContain("accent_color");
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, support_video_url: "https://vimeo.com/123" })).toContain("support_video_url");
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, homepage_url: "javascript:alert(1)" })).toContain("homepage_url");
  });

  test("blocks text contrast below 4.5 and primary below 3", () => {
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, text_color: "#BBBBBB" })).toEqual(["text_color"]);
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, surface_color: "#111111" })).toEqual(["text_color"]);
    expect(issuesOf({ ...APPEARANCE_DEFAULT_VALUES, primary_color: "#FDE68A" })).toEqual(["primary_color"]);
  });

  test("reads property rows with safe defaults", () => {
    const values = appearanceFormValues([
      { key: "primary_color", value: "#1d4ed8" },
      { key: "header_font_family", value: "POPPINS" },
      { key: "body_font_family", value: "COMIC_SANS" },
      { key: "featured_course_ids", value: ["a", 3, "b"] },
      { key: "sub_hero_enabled", value: false },
      { key: "secondary_color", value: "#000000" },
    ]);
    expect(values.primary_color).toBe("#1D4ED8");
    expect(values.header_font_family).toBe("POPPINS");
    expect(values.body_font_family).toBe("INTER");
    expect(values.featured_course_ids).toEqual(["a", "b"]);
    expect(values.sub_hero_enabled).toBe(false);
    expect(values.teacher_auto_rotate).toBe(false);
    expect(values.logo_display_size).toBe("STANDARD");
    expect(values).not.toHaveProperty("secondary_color");
  });

  test("PUT body has only changed keys; colors travel together; empty means null", () => {
    const initial = { ...APPEARANCE_DEFAULT_VALUES, primary_color: "#1D4ED8" };
    expect(toAppearanceChanges(initial, initial)).toEqual({});
    expect(toAppearanceChanges(initial, { ...initial, cta_enabled: false, homepage_url: "" })).toEqual({ cta_enabled: false });
    expect(toAppearanceChanges(initial, { ...initial, homepage_url: "https://example.com" })).toEqual({ homepage_url: "https://example.com" });
    expect(toAppearanceChanges(initial, { ...initial, text_color: "#111111" })).toEqual({
      primary_color: "#1D4ED8",
      accent_color: null,
      background_color: null,
      surface_color: null,
      text_color: "#111111",
    });
    const changes = toAppearanceChanges(initial, { ...initial, featured_course_ids: ["x"], teacher_auto_rotate: true });
    expect(changes).toEqual({ featured_course_ids: ["x"], teacher_auto_rotate: true });
    expect(changes).not.toHaveProperty("secondary_color");
  });

  test("YouTube id extraction", () => {
    expect(youTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
    expect(youTubeVideoId("https://youtu.be/dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
    expect(youTubeVideoId("http://youtu.be/dQw4w9WgXcQ")).toBeNull();
    expect(youTubeVideoId("https://evil.example/watch?v=dQw4w9WgXcQ")).toBeNull();
  });
});

test.describe("pre-upload image checks", () => {
  function rgba(width: number, height: number, paint: (x: number, y: number) => [number, number, number, number]) {
    const data = new Uint8ClampedArray(width * height * 4);
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) data.set(paint(x, y), (y * width + x) * 4);
    }
    return data;
  }

  test("content bounds detect padded logos (transparent and opaque backgrounds)", () => {
    const transparentPadded = rgba(100, 40, (x, y) => (x >= 30 && x < 70 && y >= 10 && y < 30 ? [0, 0, 0, 255] : [0, 0, 0, 0]));
    const bounds = analyzeContentBounds(transparentPadded, 100, 40);
    expect(bounds.widthCoverage).toBeCloseTo(0.4, 2);
    expect(bounds.heightCoverage).toBeCloseTo(0.5, 2);

    const whitePadded = rgba(100, 40, (x) => (x >= 10 && x < 90 ? [20, 40, 200, 255] : [255, 255, 255, 255]));
    expect(analyzeContentBounds(whitePadded, 100, 40).widthCoverage).toBeCloseTo(0.8, 2);

    const tight = rgba(50, 20, () => [10, 10, 10, 255]);
    // Uniform opaque image: the "background" is the only color, so nothing stands out.
    expect(analyzeContentBounds(tight, 50, 20).widthCoverage).toBe(0);
  });

  test("errors block wrong type and oversize; warnings never block", () => {
    expect(sniffImageType(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0, 0, 0, 0]))).toBe("image/png");
    expect(sniffImageType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe("image/jpeg");
    expect(sniffImageType(new Uint8Array([0x47, 0x49, 0x46, 0x38]))).toBeNull();

    const gif = evaluateImage("SITE_LOGO", { sizeBytes: 1000, sniffedType: null, width: 0, height: 0, bounds: null });
    expect(gif.errors).toHaveLength(1);
    const big = evaluateImage("SITE_FAVICON", { sizeBytes: 1.5 * 1024 * 1024, sniffedType: "image/png", width: 512, height: 512, bounds: null });
    expect(big.errors[0]).toContain("maximum is 1 MB");

    const lowRes = evaluateImage("SITE_LOGO", {
      sizeBytes: 1000,
      sniffedType: "image/png",
      width: 200,
      height: 60,
      bounds: { widthCoverage: 0.5, heightCoverage: 0.9 },
    });
    expect(lowRes.errors).toEqual([]);
    expect(lowRes.warnings).toHaveLength(2);

    const good = evaluateImage("SITE_LOGO", {
      sizeBytes: 1000,
      sniffedType: "image/png",
      width: 600,
      height: 180,
      bounds: { widthCoverage: 0.98, heightCoverage: 0.95 },
    });
    expect(good).toEqual({ errors: [], warnings: [] });

    const banner = evaluateImage("DESKTOP_HERO_BANNER", { sizeBytes: 1000, sniffedType: "image/jpeg", width: 800, height: 600, bounds: null });
    expect(banner.errors).toEqual([]);
    expect(banner.warnings.length).toBeGreaterThan(0);
  });
});
