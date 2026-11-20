import { CircleCheck, CircleDashed, CirclePause } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { StorefrontAdmin } from "@/lib/api/teacher-storefronts";

export type StorefrontStatus = "LIVE" | "OFF" | "NOT_SET_UP";

export function storefrontStatus(storefront: Pick<StorefrontAdmin, "id" | "enabled"> | null | undefined): StorefrontStatus {
  if (!storefront || !storefront.id) return "NOT_SET_UP";
  return storefront.enabled ? "LIVE" : "OFF";
}

const LABELS: Record<StorefrontStatus, string> = {
  LIVE: "Live",
  OFF: "Turned off",
  NOT_SET_UP: "Not set up",
};

/** STORE-01 storefront status: icon + text, never colour alone (ui-ux.md §4). */
export function StorefrontStatusBadge({ status }: { status: StorefrontStatus }) {
  if (status === "LIVE") {
    return (
      <Badge variant="default" data-testid="storefront-status">
        <CircleCheck aria-hidden="true" />
        {LABELS.LIVE}
      </Badge>
    );
  }
  return (
    <Badge variant={status === "OFF" ? "warning" : "outline"} data-testid="storefront-status">
      {status === "OFF" ? <CirclePause aria-hidden="true" /> : <CircleDashed aria-hidden="true" />}
      {LABELS[status]}
    </Badge>
  );
}
