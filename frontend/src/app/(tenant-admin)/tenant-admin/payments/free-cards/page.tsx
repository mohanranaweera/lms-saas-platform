import { redirect } from "next/navigation";
import { paymentsHubHref } from "@/lib/payments/hub-routes";

/** PAY-HUB-01: the free-card list is now the Payments hub "Free Cards" tab (all cards). Old links keep working. */
export default function Page() {
  redirect(paymentsHubHref("free-cards", { view: "all" }));
}
