import { z } from "zod";

/**
 * Zod schema for the public tenant self-registration form (`/register-institute`).
 *
 * Mirrors `TenantRegistrationRequest` (backend `com.lms.tenantmanagement.web.dto`)
 * field-for-field: `name`, `subdomain`, `requestedPlan`, `contactName`,
 * `contactEmail`, `contactPhone`. This is a UX convenience only — the backend
 * independently and authoritatively re-validates every field (including the
 * reserved-subdomain denylist below), per `.claude/rules/frontend.md`.
 */

/**
 * Mirrors `NotReservedSubdomainValidator.RESERVED_SUBDOMAINS`
 * (`backend/src/main/java/com/lms/tenantmanagement/service/NotReservedSubdomainValidator.java`).
 * Client-side convenience only — the backend independently re-checks this list and is
 * the sole authoritative enforcement point.
 */
const RESERVED_SUBDOMAINS = new Set([
  "www",
  "api",
  "admin",
  "app",
  "platform",
  "assets",
  "static",
  "mail",
  "ftp",
  "cdn",
  "docs",
  "support",
  "help",
  "status",
  "blog",
  "dev",
  "staging",
  "test",
]);

const subdomainPattern = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

export const tenantRegistrationSchema = z.object({
  name: z
    .string()
    .min(1, "Institute name is required.")
    .max(255, "Institute name must be 255 characters or fewer."),
  subdomain: z
    .string()
    .min(1, "Subdomain is required.")
    .max(63, "Subdomain must be 63 characters or fewer.")
    .regex(
      subdomainPattern,
      "Use lowercase letters, numbers, and hyphens only (must start and end with a letter or number)."
    )
    .refine((value) => !RESERVED_SUBDOMAINS.has(value.toLowerCase()), {
      message: "This subdomain is reserved for platform use. Please choose a different one.",
    }),
  requestedPlan: z
    .string()
    .min(1, "Requested plan is required.")
    .max(100, "Requested plan must be 100 characters or fewer."),
  contactName: z
    .string()
    .min(1, "Contact name is required.")
    .max(255, "Contact name must be 255 characters or fewer."),
  contactEmail: z
    .string()
    .min(1, "Contact email is required.")
    .email("Enter a valid email address.")
    .max(255, "Contact email must be 255 characters or fewer."),
  contactPhone: z
    .string()
    .max(50, "Contact phone must be 50 characters or fewer.")
    .optional()
    .or(z.literal("")),
});

export type TenantRegistrationFormValues = z.infer<typeof tenantRegistrationSchema>;
