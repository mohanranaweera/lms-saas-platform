"use client";

import { useState } from "react";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { isApiClientError } from "@/lib/api/error";
import {
  useUpdateTenantConfigDomain,
  type TenantConfigDomain,
  type TenantConfigProperty,
} from "@/lib/api/tenant-config";
import { configValue } from "@/lib/validation/tenant-config";

/**
 * Settings → Configs → Operational options (SET-CFG-01). Klass phrases both
 * as "Disable …" switches, so a switch that is ON stores `false`:
 * - Disable self-enrollment → `STUDENT.self_enrollment_enabled`. Students can
 *   no longer enroll themselves; staff still enroll them (ADR-028 refinement).
 * - Disable SMS notifications → `NOTIFICATION.sms_enabled`. SMS broadcasts are
 *   refused and queued SMS is skipped; email/WhatsApp/in-app are unaffected.
 * Both save on change, server-enforced and audit-logged.
 */
export function OperationalOptionsSection({
  studentProperties,
  notificationProperties,
  canManage,
}: {
  studentProperties: TenantConfigProperty[];
  notificationProperties: TenantConfigProperty[];
  canManage: boolean;
}) {
  const studentMutation = useUpdateTenantConfigDomain("STUDENT");
  const notificationMutation = useUpdateTenantConfigDomain("NOTIFICATION");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const pending = studentMutation.isPending || notificationMutation.isPending;

  const selfEnrollmentDisabled = configValue(studentProperties, "self_enrollment_enabled") === false;
  const smsDisabled = configValue(notificationProperties, "sms_enabled") === false;

  async function save(domain: TenantConfigDomain, changes: Record<string, unknown>, message: string) {
    setErrorMessage(null);
    setStatus(null);
    try {
      await (domain === "STUDENT" ? studentMutation : notificationMutation).mutateAsync(changes);
      setStatus(message);
    } catch (error) {
      setErrorMessage(
        isApiClientError(error)
          ? error.status === 403
            ? "You don't have permission to change configurations."
            : error.message
          : "Something went wrong. Please try again."
      );
    }
  }

  return (
    <section
      aria-labelledby="operational-options-heading"
      className="flex max-w-4xl flex-col rounded-lg border border-border"
      data-testid="operational-options"
    >
      <div className="p-4">
        <h2 id="operational-options-heading" className="text-base font-medium text-foreground">
          Operational options
        </h2>
      </div>

      <div className="flex items-start gap-3 border-t border-border p-4">
        <Switch
          id="disable-self-enrollment"
          checked={selfEnrollmentDisabled}
          disabled={!canManage || pending}
          aria-describedby="disable-self-enrollment-help"
          onCheckedChange={(disable) =>
            void save(
              "STUDENT",
              { self_enrollment_enabled: !disable },
              disable ? "Self-enrollment disabled." : "Self-enrollment enabled."
            )
          }
          className="mt-0.5"
        />
        <div className="flex flex-col gap-0.5">
          <Label htmlFor="disable-self-enrollment">Disable self-enrollment</Label>
          <p id="disable-self-enrollment-help" className="text-sm text-muted-foreground">
            Students can no longer enroll in courses themselves. Staff can still enroll them, and students who are
            already enrolled keep their courses.
          </p>
        </div>
      </div>

      <div className="flex items-start gap-3 border-t border-border p-4">
        <Switch
          id="disable-sms-notifications"
          checked={smsDisabled}
          disabled={!canManage || pending}
          aria-describedby="disable-sms-notifications-help"
          onCheckedChange={(disable) =>
            void save(
              "NOTIFICATION",
              { sms_enabled: !disable },
              disable ? "SMS notifications disabled." : "SMS notifications enabled."
            )
          }
          className="mt-0.5"
        />
        <div className="flex flex-col gap-0.5">
          <Label htmlFor="disable-sms-notifications">Disable SMS notifications</Label>
          <p id="disable-sms-notifications-help" className="text-sm text-muted-foreground">
            Stops all SMS notifications sent from the system, including ones already queued. Email, WhatsApp and
            in-app notifications are not affected.
          </p>
        </div>
      </div>

      {errorMessage || status ? (
        <div className="border-t border-border p-4">
          {errorMessage ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{errorMessage}</AlertDescription>
            </Alert>
          ) : null}
          {status ? (
            <p role="status" className="text-sm text-muted-foreground">
              {status}
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
