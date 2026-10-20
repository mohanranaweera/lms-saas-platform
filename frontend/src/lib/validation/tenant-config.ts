import { z } from "zod";

/**
 * Zod schemas for the Wave-1 Tenant Configuration Framework's two built
 * forms (General, Branding —
 * `app/(tenant-admin)/tenant-admin/settings/general/page.tsx`,
 * `.../settings/branding/page.tsx`). Mirrors `ConfigPropertyRegistry`'s
 * per-key validators
 * (`backend/.../tenantmanagement/config/ConfigPropertyRegistry.java`)
 * field-for-field, for immediate inline UX feedback only — the backend
 * remains the sole source of truth (`.claude/rules/frontend.md`), and every
 * form built against these schemas must still correctly surface a
 * server-side `400` via field errors rather than swallow it. Notably: the
 * WCAG AA contrast cross-check the backend runs on
 * `primary_color`/`secondary_color` together has NO client-side equivalent
 * here by design (per this module's own instruction not to replicate the
 * contrast math) — a contrast-failing pair always passes this schema and
 * must be caught by the real `PUT` response's field error on
 * `secondary_color`.
 *
 * Every field mirrored here is optional on the backend except
 * `institute_name` (required-ness enforced server-side, mirrored here only
 * as client-side UX) — an optional field's schema therefore always accepts
 * the empty string (meaning "leave this key out of the diffed `PUT` body",
 * see `toChangedConfigEntries` below) in addition to a well-formed value.
 */

const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const CURRENCY_CODE_PATTERN = /^[A-Z]{3}$/;
const HEX_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/;

/**
 * Mirrors `ConfigPropertyRegistry#generalProperties`'s `default_timezone`
 * validator (`ZoneId.getAvailableZoneIds().contains(s)`) as closely as a
 * browser/Node environment allows: `Intl.supportedValuesOf("timeZone")` is
 * the closest client-side equivalent to the JVM's IANA time zone database.
 * Falls back to "accept anything non-blank" when that API is unavailable
 * (older browser, or a test/runtime environment without it) rather than
 * throwing or blocking submission outright — a genuinely invalid zone id
 * still gets a real backend `400` either way, so this is a UX convenience
 * gap, not a validation hole.
 *
 * `"UTC"` — this property's own documented default value — is special-cased
 * in explicitly: the JVM's `ZoneId.getAvailableZoneIds()` includes it (Java
 * bundles a handful of non-strictly-IANA aliases like `"UTC"`/`"GMT"`
 * alongside the real tz database), but
 * `Intl.supportedValuesOf("timeZone")` does not — confirmed directly (Node
 * 24, this project's pinned engine). Without this, the very value every
 * never-customized tenant actually holds would fail client-side validation
 * while the backend accepts it.
 */
function isValidIanaTimeZone(value: string): boolean {
  if (value === "UTC") {
    return true;
  }
  try {
    const supportedValuesOf = (Intl as { supportedValuesOf?: (key: string) => string[] })
      .supportedValuesOf;
    if (typeof supportedValuesOf !== "function") {
      return true;
    }
    return supportedValuesOf("timeZone").includes(value);
  } catch {
    return true;
  }
}

/** Mirrors `ConfigPropertyRegistry#isValidHttpUrl` exactly: `http`/`https` scheme with a non-blank host. */
function isValidHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0;
  } catch {
    return false;
  }
}

function optionalPattern(pattern: RegExp, message: string) {
  return z.string().refine((value) => value === "" || pattern.test(value), { message });
}

function optionalHttpUrl(message: string) {
  return z.string().refine((value) => value === "" || isValidHttpUrl(value), { message });
}

export const generalConfigSchema = z.object({
  institute_name: z.string().min(1, "Institute name is required."),
  support_email: optionalPattern(EMAIL_PATTERN, "Enter a valid email address."),
  // No format constraint on the backend either — any string, including empty, is valid.
  support_phone: z.string(),
  default_timezone: z.string().refine((value) => value === "" || isValidIanaTimeZone(value), {
    message: 'Enter a valid IANA time zone id, e.g. "Asia/Colombo".',
  }),
  default_currency: optionalPattern(
    CURRENCY_CODE_PATTERN,
    'Enter a 3-letter currency code, e.g. "USD".'
  ),
});

export type GeneralConfigFormValues = z.infer<typeof generalConfigSchema>;

export const brandingConfigSchema = z.object({
  primary_color: optionalPattern(HEX_COLOR_PATTERN, 'Enter a hex color, e.g. "#0F172A".'),
  secondary_color: optionalPattern(HEX_COLOR_PATTERN, 'Enter a hex color, e.g. "#0F172A".'),
  logo_url: optionalHttpUrl("Enter a valid http(s) URL."),
  favicon_url: optionalHttpUrl("Enter a valid http(s) URL."),
});

export type BrandingConfigFormValues = z.infer<typeof brandingConfigSchema>;

/**
 * Computes the `PUT` body's `changes` map: only keys whose form value
 * differs from the currently-loaded server value, per
 * `TenantConfigController#updateDomain`'s "only the changed keys" contract
 * (`lib/api/tenant-config.ts#useUpdateTenantConfigDomain`). Comparison is
 * against the string form value directly (every Wave-1 property is
 * `ConfigValueType.STRING`) with the loaded `TenantConfigProperty.value`
 * coerced to a display string (`null`/`undefined` treated as `""`, matching
 * how the form itself was initialized from that same property list) — an
 * untouched field is never resent.
 */
export function toChangedConfigEntries<T extends Record<string, string>>(
  formValues: T,
  currentValues: Record<string, unknown>
): Record<string, unknown> {
  const changes: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(formValues)) {
    const current = currentValues[key];
    const currentAsString = current === null || current === undefined ? "" : String(current);
    if (value !== currentAsString) {
      changes[key] = value;
    }
  }
  return changes;
}

// ---------------------------------------------------------------------------
// PAYMENT domain - facility fee (multi-course checkout, V90)
// ---------------------------------------------------------------------------

export const FACILITY_FEE_MODES = ["NONE", "PER_COURSE", "PER_LINE", "PER_ORDER"] as const;
export type FacilityFeeMode = (typeof FACILITY_FEE_MODES)[number];

const FACILITY_FEE_AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/**
 * Mirrors `ConfigPropertyRegistry#paymentProperties`: `facility_fee_mode` is
 * one of the four modes; `facility_fee_amount` is a JSON number >= 0 with at
 * most 2 decimals. "0" is a real value (no fee), never "empty". UX only - the
 * backend re-validates the `PUT`.
 */
export const facilityFeeConfigSchema = z.object({
  facility_fee_mode: z.enum(FACILITY_FEE_MODES, { message: "Choose how the facility fee is charged" }),
  facility_fee_amount: z
    .string()
    .refine((value) => value.trim().length > 0, { message: "Enter an amount (0 for no fee)" })
    .refine((value) => value.trim().length === 0 || FACILITY_FEE_AMOUNT_PATTERN.test(value.trim()), {
      message: "Enter an amount of 0 or more with up to 2 decimals",
    }),
});

export type FacilityFeeConfigFormValues = z.infer<typeof facilityFeeConfigSchema>;

/** Current (persisted or default) values of the two keys, as form strings. */
export function facilityFeeFormValues(properties: { key: string; value: unknown; defaultValue: unknown }[]): FacilityFeeConfigFormValues {
  const read = (key: string) => {
    const property = properties.find((entry) => entry.key === key);
    if (!property) return undefined;
    return property.value ?? property.defaultValue;
  };
  const mode = String(read("facility_fee_mode") ?? "NONE");
  const amount = Number(read("facility_fee_amount") ?? 0);
  return {
    facility_fee_mode: (FACILITY_FEE_MODES as readonly string[]).includes(mode) ? (mode as FacilityFeeMode) : "NONE",
    facility_fee_amount: Number.isFinite(amount) ? amount.toFixed(2) : "0.00",
  };
}

/** The `PUT /tenant-config/PAYMENT` body: only changed keys; the amount as a JSON number (2 dp). */
export function toFacilityFeeChanges(
  values: FacilityFeeConfigFormValues,
  current: FacilityFeeConfigFormValues
): Record<string, unknown> {
  const changes: Record<string, unknown> = {};
  if (values.facility_fee_mode !== current.facility_fee_mode) changes.facility_fee_mode = values.facility_fee_mode;
  const amount = Number(Number(values.facility_fee_amount.trim()).toFixed(2));
  if (amount !== Number(current.facility_fee_amount)) changes.facility_fee_amount = amount;
  return changes;
}
