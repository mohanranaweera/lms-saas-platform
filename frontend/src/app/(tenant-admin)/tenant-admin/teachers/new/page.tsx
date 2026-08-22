"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { teacherCreateSchema, type TeacherCreateFormValues } from "@/lib/validation/teacher";
import { useCreateTeacher } from "@/lib/api/teachers";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";

/**
 * Add Teacher (Tenant Admin) — MVP-007 TCH-1. Dedicated route, not a modal
 * (plan §11). Follows `(public)/register-institute/page.tsx`'s exact form
 * structure: fieldset/legend, `aria-invalid`/`aria-describedby` wiring,
 * `aria-busy` on the form, a `role="status" aria-live="polite"`
 * submitting-announcement span, inline field errors, and a page-level
 * `ErrorState` for unmapped/non-field errors.
 *
 * `POST /v1/teachers` never activates the account itself — the created
 * teacher is `approvalStatus: PENDING` until a Tenant Admin approves it on
 * the detail page (backend-confirmed decision only, never this page).
 *
 * This route has no client-side role guard on entry (the list page hides the
 * "Add teacher" link/CTA for roles without `TEACHERS`/`CREATE_EDIT`, but a
 * direct URL visit still reaches this form) — per
 * `components/states/permission-denied-state.tsx`'s contract, this page must
 * never fabricate a denial from a client-stored role guess. Instead, a real
 * `403` from `POST /v1/teachers` is rendered via the real `PermissionDeniedState`
 * component (not folded into the generic field/page `ErrorState` bucket),
 * since "you don't have permission" and "your input was invalid" are
 * different problems for the user to act on.
 */

interface FieldConfig {
  name: keyof TeacherCreateFormValues;
  label: string;
  type?: string;
  autoComplete?: string;
  helperText?: string;
}

const FIELDS: FieldConfig[] = [
  {
    name: "name",
    label: "Full name",
    autoComplete: "name",
  },
  {
    name: "email",
    label: "Email",
    type: "email",
    autoComplete: "email",
  },
  {
    name: "password",
    label: "Temporary password",
    type: "password",
    autoComplete: "new-password",
    helperText: "Must be at least 8 characters.",
  },
];

const KNOWN_FIELD_NAMES = new Set<string>(FIELDS.map((field) => field.name));

export default function NewTeacherPage() {
  const router = useRouter();
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);
  const [permissionDeniedError, setPermissionDeniedError] = useState<ApiClientError | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<TeacherCreateFormValues>({
    resolver: zodResolver(teacherCreateSchema),
    defaultValues: {
      name: "",
      email: "",
      password: "",
    },
  });

  const mutation = useCreateTeacher();

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setPermissionDeniedError(null);
    try {
      const result = await mutation.mutateAsync(values);
      router.push(`/tenant-admin/teachers/${result.id}?created=true`);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.status === 403) {
          setPermissionDeniedError(error);
          return;
        }

        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof TeacherCreateFormValues, {
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
          // TeacherController only ever raises CONFLICT for a duplicate
          // tenant-scoped email (see TeacherController contract), so
          // attributing it to the email field is safe.
          setError("email", { type: "server", message: error.message });
          return;
        }

        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/teachers"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to teachers
        </Link>
      </div>

      {permissionDeniedError ? (
        <PermissionDeniedState
          error={permissionDeniedError}
          dashboardHref="/tenant-admin/dashboard"
        />
      ) : (
        <div className="w-full max-w-md">
          <Card>
            <CardHeader>
              <h1
                data-slot="card-title"
                className="font-heading text-base leading-snug font-medium"
              >
                Add teacher
              </h1>
              <CardDescription>
                The new teacher account is created as pending — they can&apos;t sign in until you
                approve it.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              {pageError ? (
                <ErrorState
                  message={pageError.message}
                  code={pageError.code}
                  fieldErrors={pageError.fieldErrors}
                  onRetry={() => setPageError(null)}
                />
              ) : null}
              <form
                className="flex flex-col gap-4"
                onSubmit={onSubmit}
                aria-busy={mutation.isPending}
                noValidate
              >
                <span role="status" aria-live="polite" className="sr-only">
                  {mutation.isPending ? "Creating teacher account…" : ""}
                </span>
                <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
                  <legend className="sr-only">Add teacher</legend>
                  {FIELDS.map((field) => {
                    const fieldError = errors[field.name];
                    const errorId = `new-teacher-${field.name}-error`;
                    const helperId = field.helperText
                      ? `new-teacher-${field.name}-helper`
                      : undefined;

                    return (
                      <div key={field.name} className="flex flex-col gap-1.5">
                        <Label htmlFor={`new-teacher-${field.name}`}>{field.label}</Label>
                        <Input
                          id={`new-teacher-${field.name}`}
                          type={field.type ?? "text"}
                          autoComplete={field.autoComplete}
                          aria-invalid={fieldError ? true : undefined}
                          aria-describedby={
                            [helperId, fieldError ? errorId : undefined]
                              .filter(Boolean)
                              .join(" ") || undefined
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
                  <Button type="submit" className="w-full" disabled={mutation.isPending}>
                    {mutation.isPending ? "Creating…" : "Add teacher"}
                  </Button>
                </fieldset>
              </form>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}
