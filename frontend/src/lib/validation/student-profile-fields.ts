import { z } from "zod";
import type {
  GuardianValue,
  ProfileFieldDescriptor,
  StudentProfileFieldEntry,
  StudentProfileFieldGroup,
} from "@/lib/api/student-profile-fields";

/**
 * Form helpers for tenant-configurable student profile fields (STU-PROF-01),
 * shared by the student's "Additional details" form, the staff profile tab
 * and the public registration form.
 *
 * Form values are a flat `Record<string, string>` keyed by form field name:
 * the catalogue key for simple fields, `GUARDIAN_name`/`GUARDIAN_phone` for
 * the composite guardian field, and `"true"`/`"false"`/`""` for yes/no.
 * The Zod schema mirrors the backend's per-type rules as a UX convenience
 * only; the backend re-validates everything and its field errors are mapped
 * back with {@link toFormFieldName}.
 */

export type ProfileFieldFormValues = Record<string, string>;

export const PHONE_PATTERN = /^\+?[0-9 ()-]{6,20}$/;

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const MIN_YEAR = 1990;

const MAX_YEAR = 2100;

export const GROUP_ORDER: StudentProfileFieldGroup[] = [
  "ACCOUNT",
  "PERSONAL",
  "CONTACT",
  "ADDRESS",
  "IDENTITY",
  "EDUCATION",
  "FAMILY",
  "OTHER",
];

export const GROUP_LABELS: Record<StudentProfileFieldGroup, string> = {
  ACCOUNT: "Account",
  PERSONAL: "Personal",
  CONTACT: "Contact",
  ADDRESS: "Address",
  IDENTITY: "Identity documents",
  EDUCATION: "Education",
  FAMILY: "Family",
  OTHER: "Other",
};

export function guardianNameField(key: string): string {
  return `${key}_name`;
}

export function guardianPhoneField(key: string): string {
  return `${key}_phone`;
}

/** The form field names a descriptor occupies. */
export function formFieldNames(field: ProfileFieldDescriptor): string[] {
  return field.type === "GUARDIAN" ? [guardianNameField(field.key), guardianPhoneField(field.key)] : [field.key];
}

/** Groups descriptors in display order, dropping empty groups. */
export function groupFields<T extends ProfileFieldDescriptor>(
  fields: T[]
): Array<{ group: StudentProfileFieldGroup; fields: T[] }> {
  return GROUP_ORDER.map((group) => ({ group, fields: fields.filter((field) => field.group === group) })).filter(
    (entry) => entry.fields.length > 0
  );
}

function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * A record schema validating every descriptor's value(s). `enforceRequired`
 * is `false` for staff edits (the backend does not hold staff to
 * required-ness either).
 */
export function buildProfileFieldsSchema(fields: ProfileFieldDescriptor[], options: { enforceRequired: boolean }) {
  return z.record(z.string(), z.string()).superRefine((values, ctx) => {
    const issue = (path: string, message: string) =>
      ctx.addIssue({ code: z.ZodIssueCode.custom, message, path: [path] });

    for (const field of fields) {
      const required = options.enforceRequired && field.required;

      if (field.type === "GUARDIAN") {
        const nameKey = guardianNameField(field.key);
        const phoneKey = guardianPhoneField(field.key);
        const name = (values[nameKey] ?? "").trim();
        const phone = (values[phoneKey] ?? "").trim();
        if (required && !name) issue(nameKey, `${field.label} name is required.`);
        if (required && !phone) issue(phoneKey, `${field.label} phone is required.`);
        if (name.length > field.maxLength) {
          issue(nameKey, `Must be ${field.maxLength} characters or fewer.`);
        }
        if (phone && !PHONE_PATTERN.test(phone)) issue(phoneKey, "Enter a valid phone number.");
        continue;
      }

      const value = (values[field.key] ?? "").trim();
      if (!value) {
        if (required) issue(field.key, `${field.label} is required.`);
        continue;
      }
      switch (field.type) {
        case "TEXT":
          if (value.length > field.maxLength) issue(field.key, `Must be ${field.maxLength} characters or fewer.`);
          break;
        case "PHONE":
          if (!PHONE_PATTERN.test(value)) issue(field.key, "Enter a valid phone number.");
          break;
        case "DATE":
          if (!DATE_PATTERN.test(value) || value > todayIso() || value < "1900-01-01") {
            issue(field.key, "Enter a valid past date.");
          }
          break;
        case "YEAR": {
          const year = Number(value);
          if (!/^\d{4}$/.test(value) || year < MIN_YEAR || year > MAX_YEAR) {
            issue(field.key, `Enter a year between ${MIN_YEAR} and ${MAX_YEAR}.`);
          }
          break;
        }
        case "COUNTRY":
          if (!COUNTRY_CODES.includes(value)) issue(field.key, "Choose a country.");
          break;
        case "BOOLEAN":
          if (value !== "true" && value !== "false") issue(field.key, "Choose yes or no.");
          break;
      }
    }
  });
}

/** Empty form values for a set of descriptors. */
export function emptyFormValues(fields: ProfileFieldDescriptor[]): ProfileFieldFormValues {
  return Object.fromEntries(fields.flatMap((field) => formFieldNames(field).map((name) => [name, ""])));
}

/** Form values from stored entries. */
export function toFormValues(fields: StudentProfileFieldEntry[]): ProfileFieldFormValues {
  const values: ProfileFieldFormValues = {};
  for (const field of fields) {
    if (field.type === "GUARDIAN") {
      const guardian = (field.value ?? null) as GuardianValue | null;
      values[guardianNameField(field.key)] = guardian?.name ?? "";
      values[guardianPhoneField(field.key)] = guardian?.phone ?? "";
    } else if (field.type === "BOOLEAN") {
      values[field.key] = field.value === true ? "true" : field.value === false ? "false" : "";
    } else {
      values[field.key] = field.value === null || field.value === undefined ? "" : String(field.value);
    }
  }
  return values;
}

/**
 * The request `values`/`profileFields` map for a set of descriptors: blank
 * becomes `null` (clears the stored value), or is left out when
 * `omitEmpty` (registration).
 */
export function toPayloadValues(
  fields: ProfileFieldDescriptor[],
  values: ProfileFieldFormValues,
  options: { omitEmpty?: boolean } = {}
): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const field of fields) {
    let value: unknown;
    if (field.type === "GUARDIAN") {
      const name = (values[guardianNameField(field.key)] ?? "").trim();
      const phone = (values[guardianPhoneField(field.key)] ?? "").trim();
      value = name || phone ? { name: name || null, phone: phone || null } : null;
    } else {
      const raw = (values[field.key] ?? "").trim();
      if (!raw) value = null;
      else if (field.type === "YEAR") value = Number(raw);
      else if (field.type === "BOOLEAN") value = raw === "true";
      else value = raw;
    }
    if (value === null && options.omitEmpty) continue;
    payload[field.key] = value;
  }
  return payload;
}

/**
 * Maps a backend field-error path (`values.NIC_NUMBER`,
 * `profileFields.GUARDIAN.phone`) onto a form field name, or `null` when it
 * is not a profile field.
 */
export function toFormFieldName(serverField: string, prefix: "values" | "profileFields"): string | null {
  if (!serverField.startsWith(`${prefix}.`)) return null;
  const [key, part] = serverField.slice(prefix.length + 1).split(".");
  if (!key) return null;
  if (part === "phone") return guardianPhoneField(key);
  if (part === "name") return guardianNameField(key);
  return key;
}

/** Human-readable display of a stored value (read-only views). */
export function formatProfileFieldValue(field: StudentProfileFieldEntry): string {
  const value = field.value;
  if (value === null || value === undefined || value === "") return "—";
  if (field.type === "BOOLEAN") return value === true ? "Yes" : "No";
  if (field.type === "COUNTRY") return countryName(String(value));
  if (field.type === "GUARDIAN") {
    const guardian = value as GuardianValue;
    return [guardian.name, guardian.phone].filter(Boolean).join(" · ") || "—";
  }
  return String(value);
}

/** ISO 3166-1 alpha-2 codes, matching the backend's `Locale.getISOCountries()` validation. */
export const COUNTRY_CODES: string[] = (
  "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW " +
  "BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI " +
  "FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN " +
  "IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME " +
  "MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF " +
  "PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV " +
  "SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE " +
  "YT ZA ZM ZW"
).split(" ");

let regionNames: Intl.DisplayNames | null = null;

export function countryName(code: string): string {
  try {
    regionNames ??= new Intl.DisplayNames(["en"], { type: "region" });
    return regionNames.of(code) ?? code;
  } catch {
    return code;
  }
}

/** Country options sorted by display name. */
export function countryOptions(): Array<{ code: string; name: string }> {
  return COUNTRY_CODES.map((code) => ({ code, name: countryName(code) })).sort((a, b) =>
    a.name.localeCompare(b.name)
  );
}
