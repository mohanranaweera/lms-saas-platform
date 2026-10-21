"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Trash2 } from "lucide-react";
import { useMyDevices, useRevokeMyDevice, type DeviceSlotSummary, type DeviceView } from "@/lib/api/devices";
import { isApiClientError } from "@/lib/api/error";
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

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">My devices</h1>
        <p className="text-sm text-muted-foreground">
          The browsers and devices you have signed in on. Your institute may limit how many you can use.
        </p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your devices…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.devices.length === 0}
        emptyState={{
          title: "No devices yet",
          description: "Devices appear here after you sign in on them.",
        }}
      >
        {(summary) => <MyDevicesBody summary={summary} />}
      </QueryStateBoundary>
    </div>
  );
}

function MyDevicesBody({ summary }: { summary: DeviceSlotSummary }) {
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <div className="flex flex-col gap-4">
      <DeviceLimitSummary summary={summary} audience="self" />
      {!summary.selfServiceEnabled ? (
        <p className="text-sm text-muted-foreground">
          Your institute manages your devices. To remove a device you no longer use, contact your institute.
        </p>
      ) : null}
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>
      <DeviceList
        devices={summary.devices}
        caption="Your devices"
        action={
          summary.selfServiceEnabled
            ? (device) =>
                device.status === "ACTIVE" && !device.current ? (
                  <RemoveDeviceDialog device={device} onRemoved={() => setNotice(`${device.label} was removed.`)} />
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
  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) mutation.reset();
      }}
    >
      <AlertDialogTrigger
        render={<Button type="button" variant="outline" size="sm" aria-label={`Remove ${device.label}`} />}
      >
        <Trash2 aria-hidden="true" />
        Remove
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove {device.label}?</AlertDialogTitle>
          <AlertDialogDescription>
            That device is signed out straight away. Its slot stays taken for a while before a new device can use
            it.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
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
            {mutation.isPending ? "Removing…" : "Remove device"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
