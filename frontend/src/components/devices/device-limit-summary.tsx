"use client";

import { Info } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import type { DeviceSlotSummary } from "@/lib/api/devices";
import { formatDateTime } from "@/lib/format";

import { useT } from "@/i18n/i18n-provider";
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
  const t = useT();
  if (summary.limit === null) {
    return null;
  }
  const sourceKey = summary.limitSource ?? "";
  const source =
    sourceKey === "STUDENT" ? t("devices.limitSource.student")
    : sourceKey === "COURSE" ? t("devices.limitSource.course")
    : sourceKey === "TENANT" ? t("devices.limitSource.tenant")
    : sourceKey === "PLAN" ? t("devices.limitSource.plan")
    : t("devices.limitSource.institute");
  void SOURCE_TEXT;
  const full = summary.occupiedSlots >= summary.limit;
  return (
    <Alert role="status">
      <Info aria-hidden="true" />
      <AlertTitle>
        {t("devices.slotsInUse", { count: summary.limit, used: summary.occupiedSlots })}
      </AlertTitle>
      <AlertDescription>
        <p>
          {audience === "self"
            ? t("devices.limitFromSelf", { source, hours: summary.resetCooldownHours })
            : t("devices.limitFromStaff", { source, hours: summary.resetCooldownHours })}
        </p>
        {full && summary.nextSlotFreeAt ? (
          <p>{t("devices.nextSlot", { when: formatDateTime(summary.nextSlotFreeAt) })}</p>
        ) : null}
        {full && !summary.nextSlotFreeAt ? (
          <p>
            {audience === "self"
              ? t("devices.deviceLimitSummary.signingInOnA")
              : t("devices.deviceLimitSummary.signingInOnA2")}
          </p>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}
