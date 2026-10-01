"use client";

import { useEffect, useState } from "react";
import { useStudentDevices } from "@/lib/api/devices";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DeviceList } from "@/components/devices/device-list";
import { DeviceLimitSummary } from "@/components/devices/device-limit-summary";
import { ResetDeviceDialog } from "./reset-device-dialog";
import { DeviceLimitForm } from "./device-limit-form";

/**
 * Student detail - Devices (Wave 10, PAR-03-04/PAR-16-03). Visible to roles
 * with `DEVICES`/`VIEW` (Tenant Admin, Student Support, Auditor); reset and
 * the device-limit exception are rendered only for `canManage` (Tenant
 * Admin). The backend enforces both regardless.
 */
export function DevicesTab({ studentId, canManage }: { studentId: string; canManage: boolean }) {
  const query = useStudentDevices(studentId);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <QueryStateBoundary query={query} loadingLabel="Loading devices…" loginPath="/login">
      {(summary) => {
        const hasActive = summary.devices.some((device) => device.status === "ACTIVE");
        return (
          <div className="flex flex-col gap-4">
            <DeviceLimitSummary summary={summary} audience="staff" />
            {summary.studentOverride !== null ? (
              <p className="text-sm text-muted-foreground">
                Exception: {summary.studentOverride} {summary.studentOverride === 1 ? "device" : "devices"}
                {summary.studentOverrideReason ? ` (${summary.studentOverrideReason})` : ""}
              </p>
            ) : null}
            <div role="status" aria-live="polite">
              {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
            </div>
            {canManage && hasActive ? (
              <div>
                <ResetDeviceDialog
                  studentId={studentId}
                  cooldownHours={summary.resetCooldownHours}
                  onSuccess={setNotice}
                />
              </div>
            ) : null}
            {summary.devices.length === 0 ? (
              <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
                No devices yet. A device appears here after the student signs in on it.
              </p>
            ) : (
              <DeviceList
                devices={summary.devices}
                caption="Student devices"
                showIp
                action={
                  canManage
                    ? (device) =>
                        device.status === "ACTIVE" ? (
                          <ResetDeviceDialog
                            studentId={studentId}
                            device={device}
                            cooldownHours={summary.resetCooldownHours}
                            onSuccess={setNotice}
                          />
                        ) : null
                    : undefined
                }
              />
            )}
            {canManage ? <DeviceLimitForm studentId={studentId} summary={summary} onSuccess={setNotice} /> : null}
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}
