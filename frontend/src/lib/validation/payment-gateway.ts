import { z } from "zod";

/**
 * Mirrors `PayHereAccountRequest` (PAY-GW-01) plus the service's merchant-id
 * rule (1-64 letters/digits). `merchantSecret` may be blank to keep the stored
 * secret; the "required on first save" rule is applied in the form (it
 * depends on whether a secret is already configured) and re-enforced by the
 * backend.
 */
export const payHereAccountSchema = z.object({
  merchantId: z
    .string()
    .trim()
    .min(1, "Merchant ID is required.")
    .max(64, "Merchant ID must be 64 characters or fewer.")
    .regex(/^[A-Za-z0-9]+$/, "Merchant ID may contain only letters and digits."),
  merchantSecret: z.string().max(256, "Merchant secret must be 256 characters or fewer."),
  mode: z.enum(["SANDBOX", "LIVE"]),
});

export type PayHereAccountFormValues = z.infer<typeof payHereAccountSchema>;
