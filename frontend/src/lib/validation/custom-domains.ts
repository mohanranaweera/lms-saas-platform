import { z } from "zod";

/**
 * TEN-DOM-01 — client-side check of a custom domain name. UX only: the backend
 * applies the authoritative rules (not on the platform's own domain, not
 * already taken, per-institute limit) and its field errors are shown as-is.
 */
const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const TOP_LABEL = /^[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

export function normaliseHostname(value: string): string {
  return value.trim().toLowerCase().replace(/\.+$/, "");
}

export const customDomainSchema = z.object({
  hostname: z
    .string()
    .transform(normaliseHostname)
    .refine((host) => host.length > 0, { message: "Enter a domain." })
    .refine((host) => !host.includes("/") && !host.includes(":"), {
      message: "Enter just the domain, such as learn.yourinstitute.lk (no https:// or path).",
    })
    .refine((host) => host.length <= 253, { message: "Domain must be at most 253 characters." })
    .refine(
      (host) => {
        const labels = host.split(".");
        return labels.length >= 2 && labels.every((label) => LABEL.test(label)) && TOP_LABEL.test(labels[labels.length - 1]);
      },
      { message: "Enter a full domain name, such as learn.yourinstitute.lk." }
    ),
});

export type CustomDomainFormInput = z.input<typeof customDomainSchema>;
export type CustomDomainFormValues = z.output<typeof customDomainSchema>;
