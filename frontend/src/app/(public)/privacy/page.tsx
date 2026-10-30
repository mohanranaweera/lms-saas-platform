import type { Metadata } from "next";
import { PolicyPage } from "@/components/public/policy-page";

export const metadata: Metadata = { title: "Privacy Policy" };

/** The host institute's Privacy Policy (LOGIN-01 Phase B), set in Settings → Policies. */
export default function PrivacyPolicyPage() {
  return <PolicyPage kind="privacy" />;
}
