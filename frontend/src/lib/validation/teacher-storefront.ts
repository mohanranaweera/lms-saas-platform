import { z } from "zod";

/**
 * STORE-01 / ADR-037 decision 4 - client-side hints only. The server is the
 * authority (`400 STOREFRONT_SLUG_INVALID|RESERVED`, `409 STOREFRONT_SLUG_TAKEN`)
 * and its answers are shown on the same field even when these rules pass.
 */
export const STOREFRONT_SLUG_MIN = 3;
export const STOREFRONT_SLUG_MAX = 40;

export const RESERVED_STOREFRONT_SLUGS: readonly string[] = [
  "www", "api", "admin", "app", "mail", "login", "static", "cdn", "assets", "platform",
  "auth", "student", "teacher", "tenant", "support", "help", "status", "docs", "blog",
];

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

/** First failing rule as a hint, or `null` when the slug looks valid. */
export function storefrontSlugHint(raw: string): string | null {
  const slug = raw.trim().toLowerCase();
  if (slug.length === 0) return "Enter an address for this storefront.";
  if (slug.length < STOREFRONT_SLUG_MIN || slug.length > STOREFRONT_SLUG_MAX) {
    return `Use ${STOREFRONT_SLUG_MIN} to ${STOREFRONT_SLUG_MAX} characters.`;
  }
  if (/[^a-z0-9-]/.test(slug)) return "Use only lowercase letters, numbers and hyphens.";
  if (!SLUG_PATTERN.test(slug)) return "Hyphens can't be at the start or end, or next to each other.";
  if (RESERVED_STOREFRONT_SLUGS.includes(slug)) return "This address is reserved. Choose another one.";
  return null;
}

export const storefrontSettingsSchema = z.object({
  enabled: z.boolean(),
  slug: z
    .string()
    .transform((value) => value.trim().toLowerCase())
    .superRefine((value, context) => {
      const hint = storefrontSlugHint(value);
      if (hint) context.addIssue({ code: "custom", message: hint });
    }),
});

export type StorefrontSettingsFormInput = z.input<typeof storefrontSettingsSchema>;
export type StorefrontSettingsFormValues = z.output<typeof storefrontSettingsSchema>;

export const storefrontProductsSchema = z.object({
  items: z.array(
    z.object({
      courseId: z.string().min(1),
      title: z.string(),
      listed: z.boolean(),
      published: z.boolean(),
      sortOrder: z
        .number({ message: "Enter a whole number." })
        .int("Enter a whole number.")
        .min(0, "Use 0 or more.")
        .max(9999, "Use 9999 or less."),
    })
  ),
});

export type StorefrontProductsFormValues = z.infer<typeof storefrontProductsSchema>;

/** Server message for a storefront slug error code (`null` for other codes). */
export function storefrontSlugErrorMessage(code: string): string | null {
  switch (code) {
    case "STOREFRONT_SLUG_INVALID":
      return "This address isn't valid. Use 3 to 40 lowercase letters, numbers or single hyphens.";
    case "STOREFRONT_SLUG_RESERVED":
      return "This address is reserved. Choose another one.";
    case "STOREFRONT_SLUG_TAKEN":
      return "This address is already used by another teacher at your institute, now or in the past. Choose another one.";
    default:
      return null;
  }
}
