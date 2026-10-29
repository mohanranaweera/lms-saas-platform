"use client";

import { useId, useMemo, useState } from "react";
import { Controller, useForm, useWatch, type Path, type PathValue } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, ExternalLink, Info } from "lucide-react";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { MultiSelect } from "@/components/ui/multi-select";
import { LiveRegion } from "@/components/ui/live-region";
import { ErrorState } from "@/components/states/error-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { PublicThemeScope } from "@/components/branding/public-theme-scope";
import { LOGO_DISPLAY_SIZES } from "@/components/branding/public-logo";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { useTenantConfigDomain, useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import { useTenantAsset } from "@/lib/api/tenant-assets";
import { useCourses } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { contrastReport, THEME_PRESETS } from "@/lib/theme/color";
import {
  appearanceConfigSchema,
  appearanceFormValues,
  COLOR_FIELD_DEFAULTS,
  MIN_PRIMARY_CONTRAST,
  MIN_TEXT_CONTRAST,
  themeColorsFromForm,
  toAppearanceChanges,
  type AppearanceConfigFormValues,
  type ColorField as ColorFieldKey,
} from "@/lib/validation/appearance";
import { AssetUploadField, BannerGallery } from "./asset-upload-field";
import { FontPicker } from "./font-picker";
import { ColorField, ContrastBadge } from "./theme-colors";
import { LogoSizePreview } from "./logo-size-preview";

const FIELD_LABELS: Record<string, string> = {
  homepage_url: "Institute homepage URL",
  support_video_url: "Support page video",
  featured_course_ids: "Featured courses",
  logo_display_size: "Logo display size",
  header_font_family: "Heading font",
  body_font_family: "Body font",
  primary_color: "Primary color",
  accent_color: "Accent color",
  background_color: "Page background",
  surface_color: "Surface / card background",
  text_color: "Main text",
  teacher_auto_rotate: "Auto-rotate teachers",
};

const COLOR_ROWS: { key: ColorFieldKey; label: string; description: string }[] = [
  { key: "primary_color", label: "Primary", description: "Buttons, links, active states and the CTA banner." },
  { key: "accent_color", label: "Accent", description: "Highlights and secondary emphasis." },
  { key: "background_color", label: "Page background", description: "Behind every public page section." },
  { key: "surface_color", label: "Surface / card background", description: "Cards, menus and the header." },
  { key: "text_color", label: "Main text", description: "Headings and body copy." },
];

/**
 * Tenant Admin → Settings → Appearance (docs/specs/tenant-appearance-public-home-parity.md).
 * Branding assets save immediately on upload; every other value is a
 * BRANDING config key saved by the single Save button (only changed keys are
 * sent). Read-only Auditor (VIEW) sees everything disabled. All of this is
 * re-validated and authorized server-side.
 */
const COLOR_FIELDS = ["primary_color", "accent_color", "background_color", "surface_color", "text_color"] as const;

export default function AppearanceSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("BRANDING");
  return (
    <div className="-mx-4 -my-6 flex min-h-full flex-col gap-6 bg-muted/40 px-4 py-6 md:-mx-6 md:px-6">
      <SettingsHeader active="/tenant-admin/settings/appearance" description="Appearance of your public page, logos and theme." />
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading appearance settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(properties) => <AppearanceForm properties={properties} canManage={canManage} />}
      </QueryStateBoundary>
    </div>
  );
}

function SectionCard({
  title,
  description,
  action,
  children,
  testId,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
  testId: string;
}) {
  const headingId = useId();
  return (
    <Card className="gap-0 py-0 shadow-sm" data-testid={testId} role="region" aria-labelledby={headingId}>
      <CardHeader className="border-b py-4">
        <CardTitle>
          <h2 id={headingId} className="text-base font-semibold">
            {title}
          </h2>
        </CardTitle>
        {description ? <CardDescription>{description}</CardDescription> : null}
        {action ? <CardAction>{action}</CardAction> : null}
      </CardHeader>
      <CardContent className="flex flex-col divide-y divide-border px-0">{children}</CardContent>
    </Card>
  );
}

function Row({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={className ?? "flex flex-col gap-4 px-4 py-5 sm:px-6"}>{children}</div>;
}

function SwitchRow({
  title,
  description,
  checked,
  onChange,
  disabled,
}: {
  title: string;
  description: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled: boolean;
}) {
  const id = useId();
  return (
    <div className="flex items-start gap-4 px-4 py-4 sm:px-6">
      <Switch id={id} checked={checked} onCheckedChange={onChange} disabled={disabled} aria-describedby={`${id}-desc`} className="mt-0.5" />
      <div className="flex flex-col gap-0.5">
        <label htmlFor={id} className="text-sm font-medium text-foreground">
          {title}
        </label>
        <p id={`${id}-desc`} className="text-sm text-muted-foreground">
          {description}
        </p>
      </div>
    </div>
  );
}

function TextField({
  label,
  helper,
  error,
  children,
}: {
  label: string;
  helper: string;
  error?: string;
  children: (props: { id: string; "aria-describedby": string; "aria-invalid": boolean | undefined }) => React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex max-w-xl flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-foreground">
        {label}
      </label>
      {children({ id, "aria-describedby": `${id}-helper${error ? ` ${id}-error` : ""}`, "aria-invalid": error ? true : undefined })}
      <p id={`${id}-helper`} className="text-xs text-muted-foreground">
        {helper}
      </p>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function AppearanceForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const initial = useMemo(() => appearanceFormValues(properties), [properties]);
  const mutation = useUpdateTenantConfigDomain("BRANDING");
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);
  const {
    control,
    register,
    handleSubmit,
    reset,
    setValue,
    trigger,
    formState: { errors, isDirty },
  } = useForm<AppearanceConfigFormValues>({ resolver: zodResolver(appearanceConfigSchema), defaultValues: initial, mode: "onChange" });
  const values = useWatch({ control }) as AppearanceConfigFormValues;
  const general = useTenantConfigDomain("GENERAL");
  const logo = useTenantAsset("SITE_LOGO");
  const courses = useCourses({ size: 100 });

  const instituteName = String(general.data?.find((property) => property.key === "institute_name")?.value ?? "") || "Your institute";
  const colors = themeColorsFromForm(values);
  const report = contrastReport(colors);
  const previewTheme = { ...colors, headingFont: values.header_font_family, bodyFont: values.body_font_family };
  const courseOptions = (courses.data?.content ?? []).map((course) => ({
    value: course.id,
    label: course.status === "PUBLIC" ? course.name : `${course.name} (${course.status.toLowerCase()} — not shown publicly)`,
  }));
  const disabled = !canManage || mutation.isPending;

  const onSubmit = handleSubmit(async (formValues) => {
    setSaveError(null);
    setSaved(false);
    const changes = toAppearanceChanges(initial, formValues);
    if (Object.keys(changes).length === 0) return;
    try {
      const updated = await mutation.mutateAsync(changes);
      reset(appearanceFormValues(updated));
      setSaved(true);
    } catch (error) {
      setSaveError(error);
    }
  });

  const setField = <K extends Path<AppearanceConfigFormValues>>(key: K, value: PathValue<AppearanceConfigFormValues, K>) => {
    setValue(key, value, { shouldDirty: true, shouldValidate: true });
    // Contrast rules span several color fields; re-validate all of them so no stale error survives
    // (e.g. a dark preset fixes primary-on-background only once its background is applied).
    if ((COLOR_FIELDS as readonly string[]).includes(key)) void trigger([...COLOR_FIELDS]);
  };

  const hasErrors = Object.keys(errors).length > 0;

  return (
    <form className="flex flex-col gap-6" onSubmit={onSubmit} aria-busy={mutation.isPending} noValidate>
      <LiveRegion message={mutation.isPending ? "Saving appearance settings…" : saved && !isDirty ? "Appearance settings saved." : ""} />
      {!canManage ? (
        <p className="flex items-center gap-2 rounded-lg border border-border bg-background px-4 py-3 text-sm text-muted-foreground">
          <Info className="size-4 shrink-0" aria-hidden="true" /> You have view-only access to institute appearance.
        </p>
      ) : null}

      {/* A. Branding & Media */}
      <SectionCard
        title="Branding & Media"
        description="Logos and icons save as soon as they are uploaded."
        testId="section-branding"
      >
        <Row className="grid gap-6 px-4 py-5 sm:px-6 md:grid-cols-3">
          <AssetUploadField
            label="Institute logo"
            type="SITE_LOGO"
            canManage={canManage}
            guidance="Recommended ~600×180 px wide or 512×512 square, PNG with transparent background, tightly cropped (no large empty margins). PNG/JPG, max 2 MB."
          />
          <AssetUploadField
            label="Website favicon"
            type="SITE_FAVICON"
            canManage={canManage}
            previewShape="square"
            guidance="Shown at 16–32 px in browser tabs; upload a square 512×512 PNG (min 48×48). Max 1 MB."
          />
          <AssetUploadField
            label="Payment receipt logo"
            type="RECEIPT_LOGO"
            canManage={canManage}
            guidance="Used on newly generated receipts; PNG/JPG, max 2 MB. Existing receipts are not changed."
          />
        </Row>
        <Row>
          <fieldset className="flex min-w-0 flex-col gap-3" disabled={disabled}>
            <legend className="text-sm font-medium text-foreground">Logo display size</legend>
            <p className="-mt-1 text-xs text-muted-foreground">
              Controls the logo height in the public header. The image always keeps its proportions.
            </p>
            <div className="flex flex-wrap gap-2">
              {LOGO_DISPLAY_SIZES.map((option) => (
                <label
                  key={option.value}
                  className="flex cursor-pointer items-start gap-2 rounded-lg border border-border bg-background px-3 py-2 text-sm has-checked:border-primary has-checked:bg-primary/5 has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
                >
                  <input type="radio" value={option.value} {...register("logo_display_size")} className="mt-0.5 size-4 accent-primary" />
                  <span className="flex flex-col">
                    <span className="font-medium">{option.label}</span>
                    <span className="text-xs text-muted-foreground">{option.dimensions}</span>
                  </span>
                </label>
              ))}
            </div>
            <LogoSizePreview
              logoUrl={logo.data?.previewUrl ?? null}
              instituteName={instituteName}
              size={values.logo_display_size}
              theme={previewTheme}
            />
          </fieldset>
        </Row>
        <Row>
          <TextField label="Institute homepage URL" helper="Destination when visitors click the logo in the header. Leave empty to use your public home page." error={errors.homepage_url?.message}>
            {(props) => <Input type="url" placeholder="https://www.example.com" disabled={disabled} {...props} {...register("homepage_url")} />}
          </TextField>
        </Row>
      </SectionCard>

      {/* B. Public Page Settings */}
      <SectionCard
        title="Public Page Settings"
        testId="section-public-page"
        action={
          <a href="/" target="_blank" rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
            <ExternalLink aria-hidden="true" /> Visit page
            <span className="sr-only"> (opens in a new tab)</span>
          </a>
        }
      >
        <Controller
          control={control}
          name="public_page_enabled"
          render={({ field }) => (
            <SwitchRow title="Enable Public Page" description="Show a public landing page and course directory for your institute." checked={field.value} onChange={field.onChange} disabled={disabled} />
          )}
        />
        <Controller
          control={control}
          name="sub_hero_enabled"
          render={({ field }) => (
            <SwitchRow title="Sub-Hero Section" description="Show the highlight cards (register, sign in, how to use) below the hero banner." checked={field.value} onChange={field.onChange} disabled={disabled} />
          )}
        />
        <Controller
          control={control}
          name="cta_enabled"
          render={({ field }) => (
            <SwitchRow title="Call-To-Action (CTA) Section" description="Show the call-to-action banner above the footer of the landing page." checked={field.value} onChange={field.onChange} disabled={disabled} />
          )}
        />
        <Controller
          control={control}
          name="teacher_auto_rotate"
          render={({ field }) => (
            <SwitchRow
              title="Auto-rotate teachers"
              description="Rotates the teacher carousel every 6 seconds; visitors can pause it, and it never moves for people who prefer reduced motion. Off by default."
              checked={field.value}
              onChange={field.onChange}
              disabled={disabled}
            />
          )}
        />
        <Row>
          <TextField
            label="Featured courses"
            helper={
              courses.isError
                ? "Couldn't load your courses. Reload the page to try again."
                : "Highlight selected courses on your public page. Only public courses are shown to visitors; with none selected, the first public courses are shown."
            }
            error={errors.featured_course_ids?.message}
          >
            {(props) => (
              <Controller
                control={control}
                name="featured_course_ids"
                render={({ field }) => (
                  <MultiSelect
                    id={props.id}
                    aria-describedby={props["aria-describedby"]}
                    invalid={Boolean(props["aria-invalid"])}
                    options={courseOptions}
                    value={field.value}
                    onChange={field.onChange}
                    disabled={disabled || courses.isPending}
                    placeholder={courses.isPending ? "Loading courses…" : "Choose featured courses"}
                    searchPlaceholder="Search courses"
                    emptyText="You have no courses yet."
                    testId="featured-courses"
                  />
                )}
              />
            )}
          </TextField>
        </Row>
        <Row className="flex flex-col gap-3 px-4 py-5 sm:flex-row sm:items-start sm:justify-between sm:px-6">
          <TextField label="Support page video (YouTube)" helper="YouTube video shown in the Support section of your public page. Leave empty to hide the video." error={errors.support_video_url?.message}>
            {(props) => <Input type="url" placeholder="https://www.youtube.com/watch?v=…" disabled={disabled} {...props} {...register("support_video_url")} />}
          </TextField>
          <a href="/#support" target="_blank" rel="noopener noreferrer" className={cn(buttonVariants({ variant: "outline", size: "sm" }), "w-fit sm:mt-6")}>
            <ExternalLink aria-hidden="true" /> View support page
            <span className="sr-only"> (opens in a new tab)</span>
          </a>
        </Row>
        <Row>
          <BannerGallery
            label="Desktop hero banners"
            type="DESKTOP_HERO_BANNER"
            canManage={canManage}
            guidance="Desktop: 1920×480 (4:1), min 1280×427, max 2 MB JPG/PNG. Up to 6 banners; uploads save immediately."
          />
        </Row>
        <Row>
          <BannerGallery
            label="Mobile hero banners"
            type="MOBILE_HERO_BANNER"
            canManage={canManage}
            guidance="Mobile: 800×300 (8:3), max 2 MB JPG/PNG. Up to 6 banners; uploads save immediately."
          />
        </Row>
      </SectionCard>

      {/* C. Typography */}
      <SectionCard title="Typography" description="Fonts used on your public pages only. Sinhala and Tamil text falls back to Noto Sans Sinhala / Noto Sans Tamil." testId="section-typography">
        <Row className="grid gap-8 px-4 py-5 sm:px-6 lg:grid-cols-2">
          <Controller
            control={control}
            name="header_font_family"
            render={({ field }) => (
              <FontPicker
                label="Heading font"
                helper="Page and section headings, card titles and the institute name."
                kind="heading"
                value={field.value}
                onChange={(next) => setField("header_font_family", next)}
                disabled={disabled}
              />
            )}
          />
          <Controller
            control={control}
            name="body_font_family"
            render={({ field }) => (
              <FontPicker
                label="Body font"
                helper="Paragraphs, navigation, buttons, forms and course details."
                kind="body"
                value={field.value}
                onChange={(next) => setField("body_font_family", next)}
                disabled={disabled}
              />
            )}
          />
        </Row>
      </SectionCard>

      {/* D. Theme Colors */}
      <SectionCard title="Theme Colors" description="Applied to your public pages only. Leave a color empty to use the default." testId="section-colors">
        <Row>
          <fieldset className="flex min-w-0 flex-col gap-2" disabled={disabled}>
            <legend className="text-sm font-medium text-foreground">Presets</legend>
            <p className="-mt-1 text-xs text-muted-foreground">Each preset meets WCAG AA contrast and fills all five colors.</p>
            <div className="flex flex-wrap gap-2">
              {THEME_PRESETS.map((preset) => (
                <Button
                  key={preset.id}
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setField("primary_color", preset.colors.primaryColor);
                    setField("accent_color", preset.colors.accentColor);
                    setField("background_color", preset.colors.backgroundColor);
                    setField("surface_color", preset.colors.surfaceColor);
                    setField("text_color", preset.colors.textColor);
                  }}
                >
                  <span className="flex -space-x-1" aria-hidden="true">
                    {[preset.colors.primaryColor, preset.colors.accentColor, preset.colors.backgroundColor].map((color) => (
                      <span key={color} className="size-3.5 rounded-full border border-border" style={{ backgroundColor: color }} />
                    ))}
                  </span>
                  {preset.label}
                </Button>
              ))}
            </div>
          </fieldset>
        </Row>
        <Row className="grid gap-6 px-4 py-5 sm:px-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
          <div className="grid gap-5 sm:grid-cols-2">
            {COLOR_ROWS.map((row) => (
              <ColorField
                key={row.key}
                testId={`color-${row.key}`}
                label={row.label}
                description={row.description}
                value={values[row.key] ?? ""}
                defaultValue={COLOR_FIELD_DEFAULTS[row.key]}
                onChange={(next) => setField(row.key, next)}
                error={errors[row.key]?.message}
                disabled={disabled}
              />
            ))}
          </div>
          <div className="flex flex-col gap-3">
            <h3 className="text-sm font-medium text-foreground">Contrast check</h3>
            <ul className="flex flex-col gap-1.5" aria-label="Contrast check results">
              <ContrastBadge label="Text on background" ratio={report.textOnBackground} minimum={MIN_TEXT_CONTRAST} />
              <ContrastBadge label="Text on surface" ratio={report.textOnSurface} minimum={MIN_TEXT_CONTRAST} />
              <ContrastBadge label="Primary on background" ratio={report.primaryOnBackground} minimum={MIN_PRIMARY_CONTRAST} />
              <ContrastBadge label="Label on primary" ratio={report.labelOnPrimary} minimum={MIN_TEXT_CONTRAST} />
            </ul>
            <h3 className="mt-2 text-sm font-medium text-foreground">Preview</h3>
            <PublicThemeScope theme={previewTheme} className="rounded-lg border border-border p-4" data-testid="theme-preview">
              <div className="flex flex-col gap-3 rounded-lg bg-card p-4 text-card-foreground shadow-sm ring-1 ring-border">
                <p className="text-xs font-semibold uppercase tracking-wide text-primary">Featured</p>
                <h4 className="text-lg font-semibold leading-tight">Learn with confidence</h4>
                <p className="text-sm text-muted-foreground">Secondary text uses a softened shade of your main text.</p>
                <div className="flex flex-wrap gap-2">
                  <span className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground">Register</span>
                  <span className="rounded-lg bg-(--tenant-accent) px-3 py-1.5 text-sm font-medium text-(--tenant-accent-foreground)">Accent</span>
                </div>
              </div>
            </PublicThemeScope>
          </div>
        </Row>
      </SectionCard>

      {/* E. Language */}
      <SectionCard title="Language Settings" testId="section-language">
        <Row>
          <TextField label="Default language" helper="English is currently the only supported interface language.">
            {(props) => (
              <select
                {...props}
                disabled
                value="en"
                onChange={() => undefined}
                className="h-8 w-full max-w-xs rounded-lg border border-input bg-transparent px-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-60"
              >
                <option value="en">English</option>
              </select>
            )}
          </TextField>
        </Row>
      </SectionCard>

      {saveError ? (
        <ErrorState
          message={isApiClientError(saveError) ? (saveError.status === 403 ? "You don't have permission to change appearance settings." : saveError.message) : "Unable to save appearance settings."}
          code={isApiClientError(saveError) ? saveError.code : undefined}
          fieldErrors={isApiClientError(saveError) ? saveError.fieldErrors : undefined}
          fieldLabels={FIELD_LABELS}
          onRetry={() => void onSubmit()}
        />
      ) : null}

      {canManage ? (
        <div className="sticky bottom-0 z-10 -mx-4 -mb-6 flex flex-col gap-2 border-t border-border bg-background/95 px-4 py-3 backdrop-blur sm:flex-row sm:items-center sm:justify-between md:-mx-6 md:px-6">
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {saved && !isDirty ? (
              <span className="flex items-center gap-1.5 font-medium text-foreground">
                <CheckCircle2 className="size-4 text-emerald-600" aria-hidden="true" /> Saved
              </span>
            ) : hasErrors ? (
              <span className="text-destructive">Fix the highlighted fields before saving.</span>
            ) : isDirty ? (
              "You have unsaved changes."
            ) : (
              "Logos and banners save on upload; other settings save here."
            )}
          </p>
          <div className="flex gap-2">
            <Button type="button" variant="outline" disabled={!isDirty || mutation.isPending} onClick={() => reset(initial)}>
              Discard
            </Button>
            <Button type="submit" disabled={!isDirty || hasErrors || mutation.isPending}>
              {mutation.isPending ? "Saving…" : "Save appearance"}
            </Button>
          </div>
        </div>
      ) : null}
    </form>
  );
}
