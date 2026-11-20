"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Trash2 } from "lucide-react";
import { useMyDevices, useRevokeMyDevice, type DeviceSlotSummary, type DeviceView } from "@/lib/api/devices";
import { useT } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DeviceList } from "@/components/devices/device-list";
import { DeviceLimitSummary } from "@/components/devices/device-limit-summary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

/**
 * Student → My Devices (Wave 10, PAR-16-04). Lists only the signed-in
 * student's own devices (the backend scopes `GET /devices/my` to the session).
 * Removing another device is offered only when the institute allows
 * self-service; the backend enforces that, the cooldown, and that the current
 * device can't be removed here (sign out instead).
 */
export default function MyDevicesPage() {
  const query = useMyDevices();
  const t = useT();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold break-words text-foreground">{t("student.devices.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("student.devices.subtitle")}</p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("student.devices.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.devices.length === 0}
        emptyState={{
          title: t("student.devices.empty.title"),
          description: t("student.devices.empty.description"),
        }}
      >
        {(summary) => <MyDevicesBody summary={summary} />}
      </QueryStateBoundary>
    </div>
  );
}

function MyDevicesBody({ summary }: { summary: DeviceSlotSummary }) {
  const [notice, setNotice] = useState<string | null>(null);
  const t = useT();
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <div className="flex flex-col gap-4">
      <DeviceLimitSummary summary={summary} audience="self" />
      {!summary.selfServiceEnabled ? (
        <p className="text-sm text-muted-foreground">{t("student.devices.managedByInstitute")}</p>
      ) : null}
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>
      <DeviceList
        devices={summary.devices}
        caption={t("student.devices.caption")}
        action={
          summary.selfServiceEnabled
            ? (device) =>
                device.status === "ACTIVE" && !device.current ? (
                  <RemoveDeviceDialog device={device} onRemoved={() => setNotice(t("student.devices.removed", { device: device.label }))} />
                ) : null
            : undefined
        }
      />
    </div>
  );
}

function RemoveDeviceDialog({ device, onRemoved }: { device: DeviceView; onRemoved: () => void }) {
  const mutation = useRevokeMyDevice();
  const [open, setOpen] = useState(false);
  const t = useT();
  const errorMessage = mutation.isError ? localizeApiError(t, mutation.error) : null;

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) mutation.reset();
      }}
    >
      <AlertDialogTrigger
        render={<Button type="button" variant="outline" size="sm" aria-label={t("student.devices.removeLabel", { device: device.label })} />}
      >
        <Trash2 aria-hidden="true" />
        {t("student.devices.remove")}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t("student.devices.confirmTitle", { device: device.label })}</AlertDialogTitle>
          <AlertDialogDescription>
            {t("student.devices.confirmDescription")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>{t("common.actions.cancel")}</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
            onClick={() =>
              mutation.mutate(device.id, {
                onSuccess: () => {
                  setOpen(false);
                  onRemoved();
                },
              })
            }
          >
            {mutation.isPending ? t("student.devices.removing") : t("student.devices.removeDevice")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
