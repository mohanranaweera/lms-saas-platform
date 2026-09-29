import { CheckCircle2, CircleSlash, PlugZap, RefreshCw, TriangleAlert } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { ConnectionView } from "@/lib/api/integrations";

export type ConnectionDisplayState =
  | "CONNECTED"
  | "NEEDS_RECONNECT"
  | "NOT_CONNECTED"
  | "NOT_ENABLED"
  | "UNAVAILABLE";

/** Derives what to show for a provider from backend-returned state only. */
export function connectionDisplayState(
  connection: ConnectionView | null,
  options: { enabledByTenant?: boolean; available?: boolean } = {}
): ConnectionDisplayState {
  if (options.available === false) return "UNAVAILABLE";
  if (options.enabledByTenant === false) return "NOT_ENABLED";
  if (!connection || connection.status === "DISCONNECTED") return "NOT_CONNECTED";
  if (connection.status === "NEEDS_RECONNECT") return "NEEDS_RECONNECT";
  return "CONNECTED";
}

const CONFIG: Record<
  ConnectionDisplayState,
  { label: string; variant: "default" | "warning" | "outline" | "secondary"; Icon: typeof CheckCircle2 }
> = {
  CONNECTED: { label: "Connected", variant: "default", Icon: CheckCircle2 },
  NEEDS_RECONNECT: { label: "Needs reconnect", variant: "warning", Icon: RefreshCw },
  NOT_CONNECTED: { label: "Not connected", variant: "outline", Icon: PlugZap },
  NOT_ENABLED: { label: "Not enabled by your institute", variant: "secondary", Icon: CircleSlash },
  UNAVAILABLE: { label: "Unavailable", variant: "secondary", Icon: TriangleAlert },
};

/** Status chip: text + icon, never color alone (`.claude/rules/ui-ux.md` §4). */
export function ConnectionStatusBadge({ state }: { state: ConnectionDisplayState }) {
  const { label, variant, Icon } = CONFIG[state];
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}
