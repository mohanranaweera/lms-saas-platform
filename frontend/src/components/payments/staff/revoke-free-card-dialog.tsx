"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { freeCardErrorMessage, formatYearMonth, useRevokeFreeCard, type FreeCard } from "@/lib/api/free-cards";
import { FieldError } from "./staff-dialog";
import { revokeFreeCardSchema, type RevokeFreeCardValues } from "./schemas";

/**
 * Revoke a free card (status change with a required reason - never a row
 * delete). Only the card's own coverage stops; paid entitlements are
 * untouched. Rendered only for `canRevokeFreeCard` (UX); the backend
 * enforces `FREE_CARDS/DELETE` and answers 409 if already revoked.
 */
export function RevokeFreeCardDialog({ card, onRevoked }: { card: FreeCard; onRevoked?: (message: string) => void }) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const revoke = useRevokeFreeCard();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RevokeFreeCardValues>({ resolver: zodResolver(revokeFreeCardSchema), defaultValues: { reason: "" } });

  const label = `${card.studentName ?? "student"} – ${card.courseTitle ?? "course"}`;
  const reasonId = `revoke-reason-${card.id}`;

  const onSubmit = handleSubmit(async (values) => {
    if (revoke.isPending) return;
    setError(null);
    try {
      await revoke.mutateAsync({ id: card.id, reason: values.reason.trim() });
      setOpen(false);
      reset();
      onRevoked?.(`Free card for ${label} revoked.`);
    } catch (failure) {
      setError(freeCardErrorMessage(failure));
    }
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && revoke.isPending) return;
        setOpen(next);
        if (!next) {
          setError(null);
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={
          <Button type="button" variant="destructive" size="sm" aria-label={`Revoke free card for ${label}`} data-testid="free-card-revoke" />
        }
      >
        Revoke
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
          <AlertDialogHeader>
            <AlertDialogTitle>Revoke this free card?</AlertDialogTitle>
            <AlertDialogDescription>
              {label}: {card.coveredMonths.map(formatYearMonth).join(", ")}. The student loses this card&apos;s free
              access from now on. Payments the student made are not affected. This can&apos;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={reasonId}>Reason *</Label>
            <Textarea
              id={reasonId}
              maxLength={1000}
              aria-invalid={!!errors.reason || undefined}
              aria-describedby={`${reasonId}-error`}
              data-testid="free-card-revoke-reason"
              {...register("reason")}
            />
            <FieldError id={`${reasonId}-error`} message={errors.reason?.message} />
          </div>
          {error ? (
            <Alert variant="destructive" data-testid="free-card-revoke-error">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" disabled={revoke.isPending} />}>
              Cancel
            </AlertDialogClose>
            <Button
              type="submit"
              variant="destructive"
              disabled={revoke.isPending}
              aria-busy={revoke.isPending}
              data-testid="free-card-revoke-confirm"
            >
              {revoke.isPending ? "Revoking…" : "Revoke free card"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
