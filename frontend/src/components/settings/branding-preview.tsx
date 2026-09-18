import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const HEX_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/;

export interface BrandingPreviewValues {
  instituteName: string;
  primaryColor: string;
  secondaryColor: string;
  logoUrl: string;
}

/**
 * Thin, live preview of a tenant's branding configuration, driven by the
 * exact typed values the Branding form (`branding-config-form.tsx`) already
 * holds in state — never a separately-fetched or separately-computed render
 * path, per PAR-14-02 ("must reuse production theming pipeline, never a
 * separate preview-only render path") and `.claude/rules/ui-ux.md` §2's
 * identical requirement.
 *
 * This codebase has no shared tenant-theming pipeline yet (no CSS-variable
 * override mechanism, no public-portal theming consumer built in this wave —
 * `GET /api/v1/public/tenant-config/branding` exists on the backend but
 * nothing on the frontend consumes it, per this module's own scope note).
 * Building a real "preview through the same pipeline the live site uses" is
 * therefore not yet possible without inventing that pipeline, which is
 * out of scope for this wave. This component is deliberately built as a
 * thin, values-in/render-out function of `BrandingConfigFormValues` alone —
 * no independent data fetch, no independently-derived state, no styling
 * decision this component makes on its own — so a later wave that builds
 * the real tenant-theming pipeline can swap this component's internals for
 * that pipeline without `BrandingSettingsPage` → `BrandingConfigForm` → here
 * needing to change at all.
 */
export function BrandingPreview({
  instituteName,
  primaryColor,
  secondaryColor,
  logoUrl,
}: BrandingPreviewValues) {
  const primary = HEX_COLOR_PATTERN.test(primaryColor) ? primaryColor : undefined;
  const secondary = HEX_COLOR_PATTERN.test(secondaryColor) ? secondaryColor : undefined;

  return (
    <Card role="region" aria-label="Branding preview">
      <CardHeader>
        <CardTitle>Preview</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div
          className="flex flex-col gap-3 rounded-lg border p-4"
          style={{ borderColor: primary }}
        >
          <div className="flex items-center gap-3">
            {logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element -- tenant-supplied external URL, not a build-time asset; next/image requires a configured remote pattern this preview can't assume.
              <img
                src={logoUrl}
                alt=""
                className="size-8 rounded object-contain"
                onError={(event) => {
                  event.currentTarget.style.visibility = "hidden";
                }}
              />
            ) : (
              <div
                aria-hidden="true"
                className="flex size-8 items-center justify-center rounded bg-muted text-[10px] text-muted-foreground"
              >
                Logo
              </div>
            )}
            <span className="font-semibold text-foreground" style={{ color: primary }}>
              {instituteName || "Your institute name"}
            </span>
          </div>
          <button
            type="button"
            disabled
            className="w-fit rounded-md px-3 py-1.5 text-sm font-medium text-white"
            style={{ backgroundColor: primary ?? "var(--color-muted-foreground)" }}
          >
            Primary action
          </button>
          <span
            className="w-fit rounded-md px-3 py-1 text-xs font-medium text-white"
            style={{ backgroundColor: secondary ?? "var(--color-muted-foreground)" }}
          >
            Secondary accent
          </span>
        </div>
        <p className="text-xs text-muted-foreground">
          Reflects the values currently in this form, including unsaved changes.
        </p>
      </CardContent>
    </Card>
  );
}
