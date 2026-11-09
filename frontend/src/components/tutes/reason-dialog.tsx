"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { REASON_MAX } from "@/lib/tutes/tute-form";

export const reasonSchema = z.object({
  reason: z
    .string()
    .refine((value) => value.trim().length > 0, { message: "A reason is required." })
    .refine((value) => value.trim().length <= REASON_MAX, { message: `Keep the reason to ${REASON_MAX} characters or fewer.` }),
});

type ReasonValues = z.infer<typeof reasonSchema>;

/**
 * Required-reason confirmation used by Unroll (cancel a pending copy) and
 * Release hold. The reason is recorded server-side with the actor; the
 * dialog stays open on failure so the server's message is visible. Mount
 * with a fresh `key` per open.
 */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  pending,
  error,
  testId,
  destructive,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  pending: boolean;
  error: string | null;
  testId: string;
  destructive?: boolean;
  onSubmit: (reason: string) => void;
}) {
  const ids = fieldIds(testId, "reason");
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ReasonValues>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: "" } });
  const reasonError = errors.reason?.message ?? null;
  const formId = `${testId}-form`;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title={title}
      description={description}
      testId={testId}
      className="sm:max-w-md"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            variant={destructive ? "destructive" : "default"}
            disabled={pending}
            aria-busy={pending}
            data-testid={`${testId}-submit`}
          >
            {pending ? "Saving…" : confirmLabel}
          </Button>
        </>
      }
    >
      {error ? (
        <Alert variant="destructive" className="mb-4" data-testid={`${testId}-error`}>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
      <form id={formId} noValidate onSubmit={handleSubmit((values) => onSubmit(values.reason.trim()))}>
        <FormField ids={ids} label="Reason" required error={reasonError}>
          <Textarea
            id={ids.inputId}
            rows={3}
            maxLength={REASON_MAX}
            aria-required="true"
            aria-invalid={Boolean(reasonError)}
            aria-describedby={describedBy(ids, false, Boolean(reasonError))}
            disabled={pending}
            data-testid={`${testId}-reason`}
            {...register("reason")}
          />
        </FormField>
      </form>
    </AdminFormDialog>
  );
}
