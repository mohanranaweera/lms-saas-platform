/**
 * PAY-HUB-01: the tenant-admin Payments hub lives at one route with its tab
 * (and the Free Cards sub-view / student selection) in the query string, so
 * every tab is deep-linkable. Build every hub link through here.
 */
export const PAYMENTS_HUB_PATH = "/tenant-admin/payments";

export const PAYMENTS_HUB_TABS = ["orders", "slips", "free-cards", "refunds"] as const;
export type PaymentsHubTab = (typeof PAYMENTS_HUB_TABS)[number];

export function isPaymentsHubTab(value: string | null | undefined): value is PaymentsHubTab {
  return PAYMENTS_HUB_TABS.includes(value as PaymentsHubTab);
}

/** `params` with a null/undefined/blank value are omitted. */
export function paymentsHubHref(
  tab: PaymentsHubTab,
  params: Record<string, string | null | undefined> = {}
): string {
  const search = new URLSearchParams();
  if (tab !== "orders") search.set("tab", tab);
  for (const [key, value] of Object.entries(params)) {
    if (value) search.set(key, value);
  }
  const qs = search.toString();
  return qs ? `${PAYMENTS_HUB_PATH}?${qs}` : PAYMENTS_HUB_PATH;
}
