"use client";

import { useState } from "react";
import { CheckCircle2, Clock, Smartphone, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DeviceView } from "@/lib/api/devices";

import { useT } from "@/i18n/i18n-provider";
/**
 * Device status, never colour alone (`.claude/rules/ui-ux.md` §4): text + icon.
 * A removed device whose slot is still cooling down says so.
 */
export function DeviceStatusBadge({ device }: { device: DeviceView }) {
  const t = useT();
  // Captured once per mount - the cooldown hint does not need to tick live.
  const [now] = useState(() => Date.now());
  if (device.status === "ACTIVE") {
    return device.current ? (
      <Badge variant="default">
        <Smartphone className="size-3.5" aria-hidden="true" />
        {t("devices.deviceStatusBadge.thisDevice")}
      </Badge>
    ) : (
      <Badge variant="secondary">
        <CheckCircle2 className="size-3.5" aria-hidden="true" />
        {t("devices.deviceStatusBadge.active")}
      </Badge>
    );
  }
  const coolingDown = device.slotReusableAt !== null && new Date(device.slotReusableAt).getTime() > now;
  return coolingDown ? (
    <Badge variant="warning">
      <Clock className="size-3.5" aria-hidden="true" />
      {t("devices.deviceStatusBadge.removedSlotCoolingDown")}
    </Badge>
  ) : (
    <Badge variant="outline">
      <XCircle className="size-3.5" aria-hidden="true" />
      {t("devices.deviceStatusBadge.removed")}
    </Badge>
  );
}
