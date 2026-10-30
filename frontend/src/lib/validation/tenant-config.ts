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

/** `https://` URL whose host is one of `hosts` or a subdomain of one (mirrors `isFacebookUrl`/`isYouTubeUrl`). */
function isHttpsUrlOnHosts(value: string, hosts: readonly string[]): boolean {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    return url.protocol === "https:" && hosts.some((allowed) => host === allowed || host.endsWith(`.${allowed}`));
  } catch {
    return false;
  }
}

/** Mirrors `ConfigPropertyRegistry#isShortText` (255 chars); "" means "not set". */
const MAX_TEXT_LENGTH = 255;
/** Mirrors `MAX_LONG_TEXT_LENGTH` for `institute_introduction`. */
export const MAX_INTRODUCTION_LENGTH = 2000;

function optionalText(max: number, label: string) {
  return z.string().max(max, `${label} must be ${max} characters or fewer.`);
}

function optionalSocialUrl(hosts: readonly string[], message: string) {
  return z.string().refine((value) => value === "" || isHttpsUrlOnHosts(value.trim(), hosts), { message });
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
  // SET-PAY-01: institute public information (shown on the public home page).
  address_line1: optionalText(MAX_TEXT_LENGTH, "Address line 1"),
  address_line2: optionalText(MAX_TEXT_LENGTH, "Address line 2"),
  city: optionalText(MAX_TEXT_LENGTH, "City"),
  postal_code: optionalText(MAX_TEXT_LENGTH, "Postal code"),
  institute_introduction: optionalText(MAX_INTRODUCTION_LENGTH, "Institute introduction"),
  facebook_url: optionalSocialUrl(
    ["facebook.com", "fb.com"],
    "Enter your Facebook page link, starting with https://facebook.com/"
  ),
  youtube_url: optionalSocialUrl(
    ["youtube.com", "youtu.be"],
    "Enter your YouTube channel link, starting with https://youtube.com/"
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
 * untouched field is never resent. A field emptied by the user is sent as
 * `null`, which is how the backend clears an optional key (an empty string
 * fails most keys' validators); `institute_name` stays required server-side.
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
      changes[key] = value === "" ? null : value;
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

// ---------------------------------------------------------------------------
// SET-PAY-01: Settings -> Payments (currency, bank details, bank transfer)
// ---------------------------------------------------------------------------

/**
 * Currencies offered in the "Currency students are charged in" select. The
 * backend accepts any ISO-4217-shaped code (`^[A-Z]{3}$`), so a tenant whose
 * stored code is not listed here still sees it as an extra option.
 */
export const CURRENCY_OPTIONS = [
  { code: "LKR", label: "Sri Lankan Rupee (LKR)" },
  { code: "USD", label: "US Dollar (USD)" },
  { code: "EUR", label: "Euro (EUR)" },
  { code: "GBP", label: "British Pound (GBP)" },
  { code: "AUD", label: "Australian Dollar (AUD)" },
  { code: "CAD", label: "Canadian Dollar (CAD)" },
  { code: "INR", label: "Indian Rupee (INR)" },
  { code: "SGD", label: "Singapore Dollar (SGD)" },
  { code: "AED", label: "UAE Dirham (AED)" },
  { code: "MVR", label: "Maldivian Rufiyaa (MVR)" },
] as const;

export const currencyConfigSchema = z.object({
  default_currency: z.string().regex(CURRENCY_CODE_PATTERN, "Choose a currency."),
});

export type CurrencyConfigFormValues = z.infer<typeof currencyConfigSchema>;

export const BANK_DETAIL_KEYS = ["bank_account_holder", "bank_account_number", "bank_name", "bank_branch"] as const;

/** Mirrors the four `PAYMENT.bank_*` keys (`isShortText`, 255 chars); "" clears the key. */
export const bankDetailsSchema = z.object({
  bank_account_holder: optionalText(MAX_TEXT_LENGTH, "Account holder"),
  bank_account_number: optionalText(MAX_TEXT_LENGTH, "Account number"),
  bank_name: optionalText(MAX_TEXT_LENGTH, "Bank name"),
  bank_branch: optionalText(MAX_TEXT_LENGTH, "Branch"),
});

export type BankDetailsFormValues = z.infer<typeof bankDetailsSchema>;

/** Current (persisted or default) value of one config key. */
export function configValue(
  properties: { key: string; value: unknown; defaultValue: unknown }[],
  key: string
): unknown {
  const property = properties.find((entry) => entry.key === key);
  return property ? (property.value ?? property.defaultValue) : undefined;
}

// ---------------------------------------------------------------------------
// LOGIN-01 Phase B: Settings -> Policies (GENERAL policy keys)
// ---------------------------------------------------------------------------

/** Mirrors `ConfigPropertyRegistry.MAX_POLICY_LENGTH`. */
export const MAX_POLICY_LENGTH = 50_000;

/** Plain text, "" = not published (sent as `null`, clearing the key). */
export const policiesConfigSchema = z.object({
  terms_of_service: optionalText(MAX_POLICY_LENGTH, "Terms of Service"),
  refund_policy: optionalText(MAX_POLICY_LENGTH, "Refund Policy"),
  privacy_policy: optionalText(MAX_POLICY_LENGTH, "Privacy Policy"),
});

export type PoliciesConfigFormValues = z.infer<typeof policiesConfigSchema>;
