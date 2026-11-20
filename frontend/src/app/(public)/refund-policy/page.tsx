import type { Metadata } from "next";
import { PolicyPage } from "@/components/public/policy-page";

export const metadata: Metadata = { title: "Refund Policy" };

/** The host institute's Refund Policy (LOGIN-01 Phase B), set in Settings → Policies. */
export default function RefundPolicyPage() {
  return <PolicyPage kind="refund" />;
}
