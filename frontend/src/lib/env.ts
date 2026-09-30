import { z } from "zod";

/**
 * Runtime-validated environment configuration for the frontend application.
 *
 * Only `NEXT_PUBLIC_*` variables belong here (anything read on the client must be
 * prefixed and inlined at build time by Next.js). Server-only secrets must never be
 * added to this schema/module.
 *
 * Fails fast (throws) at import time when the environment is misconfigured, so a
 * broken deployment surfaces immediately instead of failing deep inside a component.
 */
const envSchema = z.object({
  /**
   * TEN-DOM-01 / ADR-021: defaults to `/api` on the site's own address, so each
   * tenant site - platform subdomain or custom domain - reaches its own tenant
   * (the backend resolves the tenant from the host the browser used). In dev,
   * `next.config.ts` rewrites `/api` to the backend; in production Nginx does.
   * An absolute URL still works but ties every site to one host.
   */
  NEXT_PUBLIC_API_BASE_URL: z
    .string()
    .default("/api")
    .refine((value) => value.startsWith("/") || URL.canParse(value), {
      message: "NEXT_PUBLIC_API_BASE_URL must be a path such as /api or a full URL.",
    }),
});

const parsed = envSchema.safeParse({
  NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL,
});

if (!parsed.success) {
  const details = parsed.error.issues
    .map((issue) => `  - ${issue.path.join(".") || "(root)"}: ${issue.message}`)
    .join("\n");

  throw new Error(`Invalid environment configuration:\n${details}`);
}

export const env = parsed.data;
