"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import { useSetStudentDeviceLimit, type DeviceSlotSummary } from "@/lib/api/devices";
import {
  deviceLimitSchema,
  toOptionalNumber,
  type DeviceLimitFormValues,
} from "@/lib/validation/devices-access";

/**
 * Wave 10 student device-limit exception - the most specific level of the
 * precedence (student > course > tenant > plan). Empty = no exception.
 * Tenant Admin only; the backend re-checks `DEVICES`/`CREATE_EDIT`.
 */
export function DeviceLimitForm({
  studentId,
  summary,
  onSuccess,
}: {
  studentId: string;
  summary: DeviceSlotSummary;
  onSuccess: (message: string) => void;
}) {
  const mutation = useSetStudentDeviceLimit(studentId);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DeviceLimitFormValues>({
    resolver: zodResolver(deviceLimitSchema),
    defaultValues: { maxDevices: summary.studentOverride?.toString() ?? "", reason: "" },
  });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    const maxDevices = toOptionalNumber(values.maxDevices);
    mutation.mutate(
      { maxDevices, reason: values.reason },
      {
        onSuccess: (updated) => {
          reset({ maxDevices: updated.studentOverride?.toString() ?? "", reason: "" });
          onSuccess(
            maxDevices === null
              ? "The device-limit exception was removed."
              : `This student may now use ${maxDevices} ${maxDevices === 1 ? "device" : "devices"}.`
          );
        },
      }
    );
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-semibold text-foreground">Device-limit exception</h3>
        <p className="text-sm text-muted-foreground">
          Overrides the course, institute and plan limits for this student. Leave empty to remove the exception.
        </p>
      </div>
      {errorMessage ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorMessage}</AlertDescription>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-[10rem_1fr]">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="device-limit-max">Devices allowed</Label>
          <Input
            id="device-limit-max"
            type="number"
            inputMode="numeric"
            min={1}
            max={20}
            aria-invalid={errors.maxDevices ? true : undefined}
            aria-describedby={errors.maxDevices ? "device-limit-max-error" : undefined}
            {...register("maxDevices")}
          />
          {errors.maxDevices ? (
            <p id="device-limit-max-error" role="alert" className="text-xs text-destructive">
              {errors.maxDevices.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="device-limit-reason">
            Reason<span className="font-normal text-muted-foreground"> (required)</span>
          </Label>
          <Textarea
            id="device-limit-reason"
            aria-required="true"
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={errors.reason ? "device-limit-reason-error" : undefined}
            placeholder="e.g. Approved a shared family computer"
            {...register("reason")}
          />
          {errors.reason ? (
            <p id="device-limit-reason-error" role="alert" className="text-xs text-destructive">
              {errors.reason.message}
            </p>
          ) : null}
        </div>
      </div>
      <Button type="submit" className="w-full sm:w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
        {mutation.isPending ? "Saving…" : "Save exception"}
      </Button>
    </form>
  );
}
