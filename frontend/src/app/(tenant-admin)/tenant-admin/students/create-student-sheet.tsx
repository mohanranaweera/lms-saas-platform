"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import { useCreateStudent, type StudentResponse } from "@/lib/api/students";
import { studentCreateSchema, type StudentCreateFormValues } from "@/lib/validation/students";
import { isApiClientError } from "@/lib/api/error";
import { useState } from "react";
import type { FieldError } from "@/lib/api/types";

interface FieldConfig {
  name: keyof StudentCreateFormValues;
  label: string;
  type: string;
  autoComplete: string;
  helperText?: string;
}

const FIELDS: FieldConfig[] = [
  { name: "name", label: "Name", type: "text", autoComplete: "name" },
  { name: "email", label: "Email", type: "email", autoComplete: "email" },
  {
    name: "password",
    label: "Password",
    type: "password",
    autoComplete: "new-password",
    helperText: "At least 8 characters.",
  },
];

const KNOWN_FIELD_NAMES = new Set<string>(FIELDS.map((field) => field.name));
const FIELD_LABELS: Record<string, string> = Object.fromEntries(
  FIELDS.map((field) => [field.name, field.label])
);

interface CreateStudentSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /**
   * Called with the newly-created student after a successful submit, before
   * the sheet closes. The list page uses this to reset its search/status
   * filters so the new row is guaranteed visible rather than the sheet
   * closing with no visible change if an active filter happens to exclude
   * it (e.g. status filter set to "Suspended" while the new account is
   * "Active").
   */
  onCreated?: (student: StudentResponse) => void;
}

/**
 * "Add student" form, rendered inside a full-screen-on-mobile `Sheet` per
 * `.claude/rules/ui-ux.md` §5 ("Modals/drawers on mobile viewports should use
 * a full-screen sheet pattern ... for forms with meaningful input"). The
 * `Sheet` primitive already traps focus while open and returns focus to the
 * trigger on close (Base UI `Dialog`, already relied on elsewhere — see
 * `dashboard-shell.tsx`'s mobile nav drawer and `logout-control.tsx`'s
 * `AlertDialog`) — no custom focus-trap logic needed here.
 */
export function CreateStudentSheet({ open, onOpenChange, onCreated }: CreateStudentSheetProps) {
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<StudentCreateFormValues>({
    resolver: zodResolver(studentCreateSchema),
    defaultValues: { name: "", email: "", password: "" },
  });

  const mutation = useCreateStudent();

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const created = await mutation.mutateAsync(values);
      reset();
      onCreated?.(created);
      onOpenChange(false);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof StudentCreateFormValues, {
                type: "server",
                message: fieldError.message,
              });
            }
          }
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
              fieldErrors: unmapped,
            });
          }
          return;
        }

        if (error.code === "CONFLICT") {
          // StudentService only ever raises CONFLICT for a duplicate email
          // within the tenant (see backend StudentService), so attributing it
          // to this field is safe.
          setError("email", { type: "server", message: error.message });
          return;
        }

        // A stale UI could still let a create request through even when
        // "Add student" is hidden client-side — a real 403 must still be
        // handled gracefully here, not crash the form.
        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        // A submit is in flight — dismissing now (Escape/overlay-click/the
        // built-in X close button all funnel through this same callback)
        // would leave the user thinking they cancelled while the request
        // still completes in the background. Block dismissal until it
        // settles; the Cancel button below is separately disabled for the
        // same reason.
        if (!next && mutation.isPending) {
          return;
        }
        if (!next) {
          reset();
          setPageError(null);
        }
        onOpenChange(next);
      }}
    >
      <SheetContent
        side="right"
        // `sheet.tsx`'s base class ships `data-[side=right]:sm:max-w-sm` —
        // a *different* responsive modifier than `md:`, so tailwind-merge
        // does not dedupe the two against each other and both rules would
        // otherwise reach the compiled stylesheet, leaving the sheet capped
        // at 384px (not full-width) in the 640-767px band. Explicitly
        // overriding the `sm:` variant here (not just adding a `md:` one)
        // is what actually neutralizes the base rule.
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
      >
        <SheetHeader>
          <SheetTitle>Add student</SheetTitle>
          <SheetDescription>
            Create a new student account for this institute. The student will need to
            change their password on first sign-in.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4">
          {pageError ? (
            <ErrorState
              message={pageError.message}
              code={pageError.code}
              fieldErrors={pageError.fieldErrors}
              fieldLabels={FIELD_LABELS}
              // Unlike QueryStateBoundary's load-error "Try again" (which
              // re-fetches), this banner is for a failed *submission* — the
              // form's current field values are still populated (only
              // cleared on success), so retrying means resubmitting them,
              // not just dismissing the banner.
              onRetry={() => {
                setPageError(null);
                void onSubmit();
              }}
            />
          ) : null}

          <form
            id="create-student-form"
            className="flex flex-col gap-4"
            onSubmit={onSubmit}
            aria-busy={mutation.isPending}
            noValidate
          >
            <span role="status" aria-live="polite" className="sr-only">
              {mutation.isPending ? "Creating student…" : ""}
            </span>
            <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
              <legend className="sr-only">Add student</legend>
              {FIELDS.map((field) => {
                const fieldError = errors[field.name];
                const errorId = `create-student-${field.name}-error`;
                const helperId = field.helperText ? `create-student-${field.name}-helper` : undefined;

                return (
                  <div key={field.name} className="flex flex-col gap-1.5">
                    <Label htmlFor={`create-student-${field.name}`}>{field.label}</Label>
                    <Input
                      id={`create-student-${field.name}`}
                      type={field.type}
                      autoComplete={field.autoComplete}
                      aria-invalid={fieldError ? true : undefined}
                      aria-describedby={
                        [helperId, fieldError ? errorId : undefined].filter(Boolean).join(" ") ||
                        undefined
                      }
                      {...register(field.name)}
                    />
                    {field.helperText ? (
                      <p id={helperId} className="text-xs text-muted-foreground">
                        {field.helperText}
                      </p>
                    ) : null}
                    {fieldError ? (
                      <p id={errorId} role="alert" className="text-xs text-destructive">
                        {fieldError.message}
                      </p>
                    ) : null}
                  </div>
                );
              })}
            </fieldset>
          </form>
        </div>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={mutation.isPending}
            onClick={() => onOpenChange(false)}
          >
            Cancel
          </Button>
          <Button type="submit" form="create-student-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Creating…" : "Create student"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
