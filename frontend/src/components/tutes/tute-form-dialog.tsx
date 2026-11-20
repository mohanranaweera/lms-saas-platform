"use client";

import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds, nativeSelectClass } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { PaidSessionCombobox } from "@/components/tutes/paid-session-combobox";
import { isApiClientError } from "@/lib/api/error";
import { useCreateTute, useUpdateTute, type TuteView } from "@/lib/api/tutes";
import {
  DESCRIPTION_MAX,
  EMPTY_TUTE_FORM,
  NAME_MAX,
  PAID_SESSION_HELPER,
  TUTE_PAYMENT_TYPES,
  TUTE_PAYMENT_TYPE_LABELS,
  detailNumber,
  toTuteRequest,
  tuteFieldForServerError,
  tuteFormSchema,
  tuteToFormValues,
  type TuteFormValues,
} from "@/lib/tutes/tute-form";

const ID_PREFIX = "tute-form";
export const VERSION_CONFLICT_MESSAGE = "This tute was changed by someone else — reload to see the latest version.";

/**
 * Add New Tute / Edit Tute (spec §3). Field order matches Klass: Tute Name,
 * Quantity, Sell through a paid session, Payment Type, Price (only for Paid),
 * Description. Leaving Paid clears the price and the session (the server
 * refuses both on non-Paid tutes). Edit sends `version`; a 409 conflict asks
 * the user to reload instead of overwriting. Mount with a fresh `key` per open.
 */
export function TuteFormDialog({
  courseId,
  currency,
  open,
  onOpenChange,
  tute,
  onReload,
  onSaved,
}: {
  courseId: string;
  currency: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Present when editing. */
  tute: TuteView | null;
  /** Re-reads the tute after a version conflict; resolves to the fresh copy (or null if it is gone). */
  onReload?: () => Promise<TuteView | null>;
  onSaved: (saved: TuteView, mode: "create" | "edit") => void;
}) {
  const [current, setCurrent] = useState<TuteView | null>(tute);
  const [sessionTitle, setSessionTitle] = useState<string | null>(tute?.paidLessonTitle ?? null);
  const [formError, setFormError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [reloading, setReloading] = useState(false);
  const create = useCreateTute(courseId);
  const update = useUpdateTute(courseId);
  const editing = current !== null;

  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    clearErrors,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<TuteFormValues>({
    resolver: zodResolver(tuteFormSchema),
    mode: "onTouched",
    defaultValues: tute ? tuteToFormValues(tute) : EMPTY_TUTE_FORM,
  });
  const paymentType = useWatch({ control, name: "paymentType" });
  const paid = paymentType === "PAID";
  const pending = isSubmitting || create.isPending || update.isPending;

  const changePaymentType = (next: TuteFormValues["paymentType"]) => {
    setValue("paymentType", next, { shouldValidate: true, shouldDirty: true });
    if (next !== "PAID") {
      setValue("price", "", { shouldDirty: true });
      setValue("paidLessonId", "", { shouldDirty: true });
      setSessionTitle(null);
      clearErrors(["price", "paidLessonId"]);
    }
  };

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    setConflict(false);
    const body = toTuteRequest(values, current ? current.version : undefined);
    try {
      const saved = current
        ? await update.mutateAsync({ tuteId: current.id, body })
        : await create.mutateAsync(body);
      onSaved(saved, current ? "edit" : "create");
    } catch (error) {
      if (!isApiClientError(error)) {
        setFormError(mutationErrorMessage(error));
        return;
      }
      switch (error.code) {
        case "TUTE_VERSION_CONFLICT":
          setConflict(true);
          setFormError(VERSION_CONFLICT_MESSAGE);
          return;
        case "TUTE_QUANTITY_BELOW_ALLOCATED": {
          const allocated = detailNumber(error.details, "allocated");
          setError("quantity", {
            type: "server",
            message:
              allocated !== null
                ? `${allocated} ${allocated === 1 ? "copy is" : "copies are"} already assigned or dispatched, so quantity can't be lower than ${allocated}.`
                : error.message,
          });
          return;
        }
        case "TUTE_PRICE_NOT_ALLOWED":
          if (paid) setError("price", { type: "server", message: error.message });
          else setFormError(error.message);
          return;
        case "TUTE_SESSION_NOT_ELIGIBLE":
          setError("paidLessonId", { type: "server", message: error.message });
          return;
        default:
          break;
      }
      if (error.fieldErrors.length > 0) {
        const rest: string[] = [];
        for (const fieldError of error.fieldErrors) {
          const field = tuteFieldForServerError(fieldError.field);
          if (field && (field !== "price" || paid)) setError(field, { type: "server", message: fieldError.message });
          else rest.push(fieldError.message);
        }
        if (rest.length > 0) setFormError(rest.join(" "));
        return;
      }
      setFormError(mutationErrorMessage(error));
    }
  });

  const reload = async () => {
    if (!onReload) return;
    setReloading(true);
    try {
      const fresh = await onReload();
      if (fresh) {
        setCurrent(fresh);
        setSessionTitle(fresh.paidLessonTitle);
        reset(tuteToFormValues(fresh));
        setFormError(null);
        setConflict(false);
      } else {
        setFormError("This tute no longer exists.");
        setConflict(false);
      }
    } catch (error) {
      setFormError(mutationErrorMessage(error));
    } finally {
      setReloading(false);
    }
  };

  const nameIds = fieldIds(ID_PREFIX, "name");
  const quantityIds = fieldIds(ID_PREFIX, "quantity");
  const sessionIds = fieldIds(ID_PREFIX, "session");
  const typeIds = fieldIds(ID_PREFIX, "payment-type");
  const priceIds = fieldIds(ID_PREFIX, "price");
  const descriptionIds = fieldIds(ID_PREFIX, "description");
  const err = (key: keyof TuteFormValues) => errors[key]?.message ?? null;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title={editing ? "Edit Tute" : "Add New Tute"}
      testId="tute-form-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="tute-form-cancel">
            Cancel
          </Button>
          <Button type="submit" form="tute-form" disabled={pending || reloading} aria-busy={pending} data-testid="tute-form-submit">
            {pending ? "Saving…" : editing ? "Save Changes" : "Create Tute"}
          </Button>
        </>
      }
    >
      {formError ? (
        <Alert variant="destructive" className="mb-4" data-testid="tute-form-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription className="flex flex-col items-start gap-2">
            <span>{formError}</span>
            {conflict && onReload ? (
              <Button type="button" size="sm" variant="outline" onClick={() => void reload()} disabled={reloading} data-testid="tute-form-reload">
                {reloading ? "Reloading…" : "Reload"}
              </Button>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      <form id="tute-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <FormField ids={nameIds} label="Tute Name" required error={err("name")}>
            <Input
              id={nameIds.inputId}
              maxLength={NAME_MAX}
              autoComplete="off"
              aria-required="true"
              aria-invalid={Boolean(err("name"))}
              aria-describedby={describedBy(nameIds, false, Boolean(err("name")))}
              data-testid="tute-form-name"
              {...register("name")}
            />
          </FormField>

          <FormField ids={quantityIds} label="Quantity" required error={err("quantity")} helper="Total printed copies the institute holds for this tute.">
            <Input
              id={quantityIds.inputId}
              type="number"
              inputMode="numeric"
              min={0}
              step={1}
              aria-required="true"
              aria-invalid={Boolean(err("quantity"))}
              aria-describedby={describedBy(quantityIds, true, Boolean(err("quantity")))}
              data-testid="tute-form-quantity"
              {...register("quantity")}
            />
          </FormField>

          <FormField
            ids={sessionIds}
            label="Sell through a paid session (optional)"
            error={err("paidLessonId")}
            helper={PAID_SESSION_HELPER}
          >
            <Controller
              control={control}
              name="paidLessonId"
              render={({ field }) => (
                <PaidSessionCombobox
                  courseId={courseId}
                  inputId={sessionIds.inputId}
                  value={field.value}
                  selectedTitle={sessionTitle}
                  invalid={Boolean(err("paidLessonId"))}
                  describedById={describedBy(sessionIds, true, Boolean(err("paidLessonId")))}
                  disabled={pending}
                  onChange={(session) => {
                    field.onChange(session ? session.lessonId : "");
                    setSessionTitle(session ? session.title : null);
                    clearErrors("paidLessonId");
                    // A paid session only applies to a Paid tute.
                    if (session && paymentType !== "PAID") {
                      setValue("paymentType", "PAID", { shouldValidate: true, shouldDirty: true });
                    }
                  }}
                />
              )}
            />
          </FormField>

          <FormField ids={typeIds} label="Payment Type" required error={err("paymentType")}>
            <Controller
              control={control}
              name="paymentType"
              render={({ field }) => (
                <select
                  id={typeIds.inputId}
                  ref={field.ref}
                  name={field.name}
                  className={nativeSelectClass}
                  value={field.value}
                  onBlur={field.onBlur}
                  aria-required="true"
                  aria-invalid={Boolean(err("paymentType"))}
                  aria-describedby={describedBy(typeIds, false, Boolean(err("paymentType")))}
                  onChange={(event) => changePaymentType(event.target.value as TuteFormValues["paymentType"])}
                  data-testid="tute-form-payment-type"
                >
                  <option value="">Select a payment type</option>
                  {TUTE_PAYMENT_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {TUTE_PAYMENT_TYPE_LABELS[type]}
                    </option>
                  ))}
                </select>
              )}
            />
          </FormField>

          {paid ? (
            <FormField
              ids={priceIds}
              label={currency ? `Price (${currency})` : "Price"}
              required
              error={err("price")}
              helper="Shown for reference. Assigning a tute never charges the student."
            >
              <Input
                id={priceIds.inputId}
                type="text"
                inputMode="decimal"
                placeholder="0.00"
                autoComplete="off"
                aria-required="true"
                aria-invalid={Boolean(err("price"))}
                aria-describedby={describedBy(priceIds, true, Boolean(err("price")))}
                data-testid="tute-form-price"
                {...register("price")}
              />
            </FormField>
          ) : null}

          <FormField ids={descriptionIds} label="Description" error={err("description")}>
            <Textarea
              id={descriptionIds.inputId}
              rows={3}
              maxLength={DESCRIPTION_MAX}
              aria-invalid={Boolean(err("description"))}
              aria-describedby={describedBy(descriptionIds, false, Boolean(err("description")))}
              data-testid="tute-form-description"
              {...register("description")}
            />
          </FormField>
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
