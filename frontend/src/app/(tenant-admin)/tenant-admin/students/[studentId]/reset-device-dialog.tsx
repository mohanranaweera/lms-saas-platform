"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { isApiClientError } from "@/lib/api/error";
import { useResetAllStudentDevices, useResetStudentDevice, type DeviceView } from "@/lib/api/devices";
import { deviceResetSchema, type DeviceResetFormValues } from "@/lib/validation/devices-access";

interface ResetDeviceDialogProps {
  studentId: string;
  /** One device, or every active device of the student when omitted. */
  device?: DeviceView;
  cooldownHours: number;
  onSuccess: (message: string) => void;
}

/**
 * Wave 10 device reset (one device or all). A mandatory reason is recorded
 * in the audit log; the backend signs the device out, starts the reset
 * cooldown and re-checks `DEVICES`/`CREATE_EDIT`.
 */
export function ResetDeviceDialog({ studentId, device, cooldownHours, onSuccess }: ResetDeviceDialogProps) {
  const resetOne = useResetStudentDevice(studentId);
  const resetAll = useResetAllStudentDevices(studentId);
  const mutation = device ? resetOne : resetAll;
  const [open, setOpen] = useState(false);
  const idBase = `reset-device-${device?.id ?? "all"}`;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DeviceResetFormValues>({
    resolver: zodResolver(deviceResetSchema),
    defaultValues: { reason: "" },
  });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    const done = () => {
      reset();
      setOpen(false);
    };
    if (device) {
      resetOne.mutate(
        { deviceId: device.id, reason: values.reason },
        {
          onSuccess: () => {
            done();
            onSuccess(`${device.label} was reset.`);
          },
        }
      );
    } else {
      resetAll.mutate(
        { reason: values.reason },
        {
          onSuccess: (result) => {
            done();
            onSuccess(
              result.revokedCount === 1 ? "1 device was reset." : `${result.revokedCount} devices were reset.`
            );
          },
        }
      );
    }
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          mutation.reset();
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={
          <Button
            type="button"
            variant={device ? "outline" : "destructive"}
            size="sm"
            aria-label={device ? `Reset ${device.label}` : "Reset all devices"}
          />
        }
      >
        <RotateCcw aria-hidden="true" />
        {device ? "Reset" : "Reset all devices"}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{device ? `Reset ${device.label}?` : "Reset all devices?"}</AlertDialogTitle>
          <AlertDialogDescription>
            {device ? "The device is" : "Every device is"} signed out immediately. A freed slot can be used by a new
            device after {cooldownHours} {cooldownHours === 1 ? "hour" : "hours"}.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <form onSubmit={onSubmit} className="flex flex-col gap-1.5" noValidate>
          <Label htmlFor={`${idBase}-reason`}>
            Reason<span className="font-normal text-muted-foreground"> (required)</span>
          </Label>
          <Textarea
            id={`${idBase}-reason`}
            aria-required="true"
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={errors.reason ? `${idBase}-reason-error` : undefined}
            placeholder="e.g. Student replaced their phone"
            {...register("reason")}
          />
          {errors.reason ? (
            <p id={`${idBase}-reason-error`} role="alert" className="text-xs text-destructive">
              {errors.reason.message}
            </p>
          ) : null}
        </form>
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={() => void onSubmit()}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Resetting…" : device ? "Reset device" : "Reset all"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
