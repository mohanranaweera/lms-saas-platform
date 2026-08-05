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
  NEXT_PUBLIC_API_BASE_URL: z
    .string({ error: "NEXT_PUBLIC_API_BASE_URL is required." })
    .url({ message: "NEXT_PUBLIC_API_BASE_URL must be a valid URL." }),
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
