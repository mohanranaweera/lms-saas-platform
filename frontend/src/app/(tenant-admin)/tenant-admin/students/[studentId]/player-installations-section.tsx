"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Ban } from "lucide-react";
import { Badge } from "@/components/ui/badge";
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
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { isApiClientError } from "@/lib/api/error";
import {
  useRevokeStudentInstallation,
  useStudentPlayerInstallations,
  type PlayerInstallation,
} from "@/lib/api/player";
import { deviceResetSchema, type DeviceResetFormValues } from "@/lib/validation/devices-access";

const REVOCATION_LABELS: Record<string, string> = {
  STUDENT: "removed by the student",
  ADMIN: "removed by staff",
  DEVICE_REVOKED: "its device was reset",
  REPAIRED: "replaced by a newer pairing",
};

/**
 * Student detail - Devices tab - AOC Player (VIDEO Phase 8). Lists the
 * student's paired players (`DEVICES`/`VIEW`); Tenant Admin can remove one with
 * an audited reason (`DEVICES`/`CREATE_EDIT`), which stops anything playing in
 * it. A player shares its device's slot, so it never changes the device count.
 */
export function PlayerInstallationsSection({
  studentId,
  canManage,
  onSuccess,
}: {
  studentId: string;
  canManage: boolean;
  onSuccess: (message: string) => void;
}) {
  const query = useStudentPlayerInstallations(studentId);

  return (
    <section aria-labelledby="aoc-player-heading" className="flex flex-col gap-2">
      <h3 id="aoc-player-heading" className="text-sm font-semibold text-foreground">
        AOC Player
      </h3>
      <QueryStateBoundary query={query} loadingLabel="Loading AOC Players…" loginPath="/login">
        {(installations) =>
          installations.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border p-4 text-center text-sm text-muted-foreground">
              No AOC Player paired. A player appears here after the student pairs it on one of their devices.
            </p>
          ) : (
            <ul className="flex flex-col gap-2" aria-label="Student AOC Players">
              {installations.map((installation) => (
                <li
                  key={installation.id}
                  className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0 text-sm">
                    <p className="flex flex-wrap items-center gap-2 font-medium text-foreground">
                      Windows · v{installation.playerVersion}
                      <Badge variant={installation.status === "ACTIVE" ? "default" : "outline"}>
                        {installation.status === "ACTIVE" ? "Active" : "Removed"}
                      </Badge>
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Paired {formatDate(installation.pairedAt)} · last used {formatDate(installation.lastSeenAt)}
                      {installation.status === "REVOKED" && installation.revocationSource
                        ? ` · ${REVOCATION_LABELS[installation.revocationSource] ?? "removed"}`
                        : ""}
                    </p>
                  </div>
                  {canManage && installation.status === "ACTIVE" ? (
                    <RemoveInstallationDialog studentId={studentId} installation={installation} onSuccess={onSuccess} />
                  ) : null}
                </li>
              ))}
            </ul>
          )
        }
      </QueryStateBoundary>
    </section>
  );
}

function RemoveInstallationDialog({
  studentId,
  installation,
  onSuccess,
}: {
  studentId: string;
  installation: PlayerInstallation;
  onSuccess: (message: string) => void;
}) {
  const mutation = useRevokeStudentInstallation(studentId);
  const [open, setOpen] = useState(false);
  const reasonId = `remove-player-${installation.id}-reason`;
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DeviceResetFormValues>({ resolver: zodResolver(deviceResetSchema), defaultValues: { reason: "" } });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(
      { installationId: installation.id, reason: values.reason },
      {
        onSuccess: () => {
          reset();
          setOpen(false);
          onSuccess("The AOC Player was removed.");
        },
      }
    );
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
        render={<Button type="button" variant="outline" size="sm" aria-label="Remove this AOC Player" />}
      >
        <Ban aria-hidden="true" />
        Remove
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <AlertDialogHeader>
            <AlertDialogTitle>Remove this AOC Player?</AlertDialogTitle>
            <AlertDialogDescription>
              Anything playing in it stops straight away. The student can pair a player again later.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={reasonId}>Reason</Label>
            <Textarea
              id={reasonId}
              rows={2}
              aria-invalid={errors.reason ? true : undefined}
              aria-describedby={errors.reason ? `${reasonId}-error` : undefined}
              {...register("reason")}
            />
            {errors.reason ? (
              <p id={`${reasonId}-error`} className="text-xs text-destructive">
                {errors.reason.message}
              </p>
            ) : null}
          </div>
          {errorMessage ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{errorMessage}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button type="submit" variant="destructive" disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Removing…" : "Remove player"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
