import { redirect } from "next/navigation";
import { paymentsHubHref } from "@/lib/payments/hub-routes";

/** PAY-HUB-01: the Payment dashboard is now the Payments hub "All Orders" tab. Old links keep working. */
export default function Page() {
  redirect(paymentsHubHref("orders"));
}
