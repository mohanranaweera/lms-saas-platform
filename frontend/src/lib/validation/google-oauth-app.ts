import { z } from "zod";

/**
 * VIDEO Phase 5c - the platform Google OAuth app form. Mirrors
 * `GoogleOAuthAppService` (a Google web client id, a secret without spaces,
 * both ≤ 255). UX convenience only - the backend re-validates.
 */
export const googleOAuthAppSchema = z.object({
  clientId: z
    .string()
    .trim()
    .min(1, "Enter the client ID.")
    .max(255, "Use at most 255 characters.")
    .regex(/^[A-Za-z0-9._-]+\.apps\.googleusercontent\.com$/, "A Google client ID ends in .apps.googleusercontent.com."),
  clientSecret: z
    .string()
    .trim()
    .min(1, "Enter the client secret.")
    .max(255, "Use at most 255 characters.")
    .regex(/^\S+$/, "The client secret can't contain spaces."),
});

export type GoogleOAuthAppFormValues = z.infer<typeof googleOAuthAppSchema>;
