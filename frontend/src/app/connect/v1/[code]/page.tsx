import type { Metadata } from "next";
import { ConnectInstitute } from "./connect-institute";

export const metadata: Metadata = {
  title: "Connect to your institute",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

/**
 * INST-QR-01 web fallback for the Institute QR (contract:
 * `docs/api/institute-connection.md`). Only the path code is used; query
 * parameters (e.g. a `redirect`) are ignored by design.
 */
export default async function ConnectPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <ConnectInstitute code={code} />;
}
