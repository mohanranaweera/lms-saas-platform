"use client";

import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { MoneyInput } from "@/components/courses/admin/money-input";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { moduleFormSchema, type ModuleFormValues } from "@/components/courses/admin/schemas";
import { isApiClientError } from "@/lib/api/error";
import {
  formatAmount,
  toMoneyString,
  useCreateKlassModule,
  useUpdateKlassModule,
  type KlassModule,
} from "@/lib/api/klass-admin";

const ID_PREFIX = "module-form";

/**
 * Add New Module / Edit Module modal. Cancel or X creates nothing. Price is
 * optional and is what students pay for the module on Pay For Module courses.
 * Mount with a fresh `key` per open.
 */
export function ModuleFormDialog({
  courseId,
  open,
  onOpenChange,
  module,
  nextSequence,
  onSaved,
}: {
  courseId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Present when editing. */
  module: KlassModule | null;
  nextSequence: number;
  onSaved: (saved: KlassModule, mode: "create" | "edit") => void;
}) {
  const [formError, setFormError] = useState<string | null>(null);
  const create = useCreateKlassModule(courseId);
  const update = useUpdateKlassModule(courseId);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ModuleFormValues>({
    resolver: zodResolver(moduleFormSchema),
    mode: "onTouched",
    defaultValues: { name: module?.title ?? "", price: formatAmount(module?.price) ?? "" },
  });
  const pending = isSubmitting || create.isPending || update.isPending;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const body = {
      title: values.name.trim(),
      sequence: module?.sequence ?? nextSequence,
      price: values.price.trim() ? toMoneyString(values.price.trim()) : null,
    };
    try {
      const saved = module
        ? await update.mutateAsync({ moduleId: module.id, body })
        : await create.mutateAsync(body);
      onSaved(saved, module ? "edit" : "create");
    } catch (error) {
      if (isApiClientError(error) && error.fieldErrors.length > 0) {
        const rest: string[] = [];
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "title" || fieldError.field === "name") {
            setError("name", { type: "server", message: fieldError.message });
          } else if (fieldError.field === "price") {
            setError("price", { type: "server", message: fieldError.message });
          } else {
            rest.push(fieldError.message);
          }
        }
        if (rest.length > 0) setFormError(rest.join(" "));
      } else {
        setFormError(mutationErrorMessage(error));
      }
    }
  });

  const nameIds = fieldIds(ID_PREFIX, "name");
  const priceIds = fieldIds(ID_PREFIX, "price");
  const nameError = errors.name?.message ?? null;
  const priceError = errors.price?.message ?? null;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title={module ? "Edit Module" : "Add New Module"}
      testId="module-form-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="module-form-cancel">
            Cancel
          </Button>
          <Button type="submit" form="module-form" disabled={pending} aria-busy={pending} data-testid="module-form-submit">
            {pending ? "Saving…" : module ? "Save module" : "Create module"}
          </Button>
        </>
      }
    >
      {formError ? (
        <Alert variant="destructive" className="mb-4" data-testid="module-form-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}
      <form id="module-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <FormField ids={nameIds} label="Name" required error={nameError}>
            <Input
              id={nameIds.inputId}
              maxLength={255}
              autoComplete="off"
              aria-required="true"
              aria-invalid={Boolean(nameError)}
              aria-describedby={describedBy(nameIds, false, Boolean(nameError))}
              data-testid="module-form-name"
              {...register("name")}
            />
          </FormField>
          <FormField
            ids={priceIds}
            label="Price"
            error={priceError}
            helper="Optional. What students pay for this module when the course payment type is Pay For Module."
          >
            <Controller
              control={control}
              name="price"
              render={({ field }) => (
                <MoneyInput
                  id={priceIds.inputId}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  invalid={Boolean(priceError)}
                  describedBy={describedBy(priceIds, true, Boolean(priceError))}
                  testId="module-form-price"
                />
              )}
            />
          </FormField>
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
