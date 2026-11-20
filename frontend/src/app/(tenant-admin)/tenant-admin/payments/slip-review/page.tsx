import { redirect } from "next/navigation";
import { paymentsHubHref } from "@/lib/payments/hub-routes";

/** PAY-HUB-01: the slip review queue is now the Payments hub "Payment Slips" tab (slip detail pages are unchanged). Old links keep working. */
export default function Page() {
  redirect(paymentsHubHref("slips"));
}
