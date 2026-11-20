import type { Metadata } from "next";
import { notFound } from "next/navigation";

export const metadata: Metadata = { title: "Storefront not found", robots: { index: false } };

/**
 * STORE-01: `src/proxy.ts` rewrites every page request on an unknown,
 * disabled or retired storefront host here. `notFound()` renders this
 * segment's `not-found.tsx` with a 404 status - never the tenant catalog.
 */
export default function StorefrontNotFoundPage() {
  notFound();
}
