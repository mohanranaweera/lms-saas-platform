import { redirect } from "next/navigation";
import { paymentsHubHref } from "@/lib/payments/hub-routes";

/** PAY-HUB-01: Refunds is now a Payments hub tab. Old links keep working. */
export default function Page() {
  redirect(paymentsHubHref("refunds"));
}
