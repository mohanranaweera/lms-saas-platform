import { Info } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import type { DeviceSlotSummary } from "@/lib/api/devices";
import { formatDateTime } from "@/lib/format";

const SOURCE_TEXT: Record<string, string> = {
  STUDENT: "a personal exception",
  COURSE: "a course setting",
  TENANT: "your institute setting",
  PLAN: "the standard plan",
};

/**
 * "2 of 3 device slots in use" plus why, and when a cooling-down slot frees
 * up. Rendered only for device-limited (student) accounts.
 */
export function DeviceLimitSummary({ summary, audience }: { summary: DeviceSlotSummary; audience: "self" | "staff" }) {
  if (summary.limit === null) {
    return null;
  }
  const source = summary.limitSource ? SOURCE_TEXT[summary.limitSource] : null;
  const full = summary.occupiedSlots >= summary.limit;
  return (
    <Alert role="status">
      <Info aria-hidden="true" />
      <AlertTitle>
        {summary.occupiedSlots} of {summary.limit} device {summary.limit === 1 ? "slot" : "slots"} in use
      </AlertTitle>
      <AlertDescription>
        <p>
          {audience === "self" ? "Your" : "This student's"} limit comes from {source ?? "the institute"}. A removed
          device keeps its slot for {summary.resetCooldownHours} hours before a new device can use it.
        </p>
        {full && summary.nextSlotFreeAt ? (
          <p>The next slot frees up at {formatDateTime(summary.nextSlotFreeAt)}.</p>
        ) : null}
        {full && !summary.nextSlotFreeAt ? (
          <p>
            {audience === "self"
              ? "Signing in on a new device is blocked until a device is removed."
              : "Signing in on a new device is blocked until a device is reset."}
          </p>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}
