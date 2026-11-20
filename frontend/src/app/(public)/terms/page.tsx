import type { Metadata } from "next";
import { PolicyPage } from "@/components/public/policy-page";

export const metadata: Metadata = { title: "Terms of Service" };

/** The host institute's Terms of Service (LOGIN-01 Phase B), set in Settings → Policies. */
export default function TermsPage() {
  return <PolicyPage kind="terms" />;
}
