import { test, expect } from "@playwright/test";
import {
  DARK_THEME_BASE,
  contrastRatio,
  darkThemeColors,
  resolveThemeColors,
  themeScopeCssVariables,
} from "../../src/lib/theme/color";

/**
 * Unit tests (no browser) for the LOGIN-01 Phase A dark tenant palette: the
 * tenant's brand colours survive, its light background/surface/text never
 * leak into dark mode, and primary/accent always reach 3:1 on the dark
 * background.
 */

test("dark palette keeps a brand colour that already contrasts and replaces the tenant's light surfaces", () => {
  const colors = resolveThemeColors({ primaryColor: "#60A5FA", backgroundColor: "#FFFFFF", textColor: "#0F172A" });
  const dark = darkThemeColors(colors);
  expect(dark.primaryColor).toBe("#60A5FA");
  expect(dark.backgroundColor).toBe(DARK_THEME_BASE.backgroundColor);
  expect(dark.surfaceColor).toBe(DARK_THEME_BASE.surfaceColor);
  expect(dark.textColor).toBe(DARK_THEME_BASE.textColor);
});

for (const brand of ["#1D4ED8", "#15803D", "#0B3D2E", "#000000", "#7F1D1D"]) {
  test(`dark primary/accent derived from ${brand} reach 3:1 on the dark background`, () => {
    const dark = darkThemeColors(resolveThemeColors({ primaryColor: brand, accentColor: brand }));
    expect(contrastRatio(dark.primaryColor, DARK_THEME_BASE.backgroundColor)).toBeGreaterThanOrEqual(3);
    expect(contrastRatio(dark.accentColor, DARK_THEME_BASE.backgroundColor)).toBeGreaterThanOrEqual(3);
  });
}

test("scope variables carry both palettes and never the bare tokens", () => {
  const vars = themeScopeCssVariables(resolveThemeColors({ primaryColor: "#15803D", backgroundColor: "#F7FBF8" }));
  expect(vars["--tl-primary"]).toBe("#15803D");
  expect(vars["--tl-background"]).toBe("#F7FBF8");
  expect(vars["--td-background"]).toBe(DARK_THEME_BASE.backgroundColor);
  expect(vars["--primary"]).toBeUndefined();
  expect(vars["--background"]).toBeUndefined();
});
