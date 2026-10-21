"use client";

import { useState } from "react";
import { CheckCircle2, Clock, Smartphone, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DeviceView } from "@/lib/api/devices";

/**
 * Device status, never colour alone (`.claude/rules/ui-ux.md` §4): text + icon.
 * A removed device whose slot is still cooling down says so.
 */
export function DeviceStatusBadge({ device }: { device: DeviceView }) {
  // Captured once per mount - the cooldown hint does not need to tick live.
  const [now] = useState(() => Date.now());
  if (device.status === "ACTIVE") {
    return device.current ? (
      <Badge variant="default">
        <Smartphone className="size-3.5" aria-hidden="true" />
        This device
      </Badge>
    ) : (
      <Badge variant="secondary">
        <CheckCircle2 className="size-3.5" aria-hidden="true" />
        Active
      </Badge>
    );
  }
  const coolingDown = device.slotReusableAt !== null && new Date(device.slotReusableAt).getTime() > now;
  return coolingDown ? (
    <Badge variant="warning">
      <Clock className="size-3.5" aria-hidden="true" />
      Removed - slot cooling down
    </Badge>
  ) : (
    <Badge variant="outline">
      <XCircle className="size-3.5" aria-hidden="true" />
      Removed
    </Badge>
  );
}
