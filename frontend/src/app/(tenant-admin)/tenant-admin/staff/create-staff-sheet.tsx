"use client";

import { useState } from "react";
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
import { useCreateStaff, type StaffResponse } from "@/lib/api/staff";
import { useAssignableRoles } from "@/lib/api/roles";
import { staffCreateSchema, type StaffCreateFormValues } from "@/lib/validation/staff";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";

const KNOWN_FIELD_NAMES = new Set<string>(["name", "email", "password", "roleCode"]);
const FIELD_LABELS: Record<string, string> = {
  name: "Name",
  email: "Email",
  password: "Password",
  roleCode: "Role",
};

interface CreateStaffSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called with the newly-created staff account after a successful submit, before the sheet closes — mirrors `create-student-sheet.tsx#onCreated`. */
  onCreated?: (staff: StaffResponse) => void;
}

/**
 * "Add staff" form, rendered inside a full-screen-on-mobile `Sheet` — same
 * pattern as `students/create-student-sheet.tsx` (`.claude/rules/ui-ux.md`
 * §5's "modals/drawers on mobile use a full-screen sheet pattern" rule).
 * Only ever rendered by the caller when `canManageStaff(role)` is true (UX
 * convenience only — `POST /api/v1/staff` independently re-enforces
 * `STAFF_AND_ROLES`/`CREATE_EDIT` server-side).
 *
 * The `roleCode` field is a native `<select>` (same convention as
 * `students/page.tsx`'s status filter) populated from the live
 * `GET /v1/roles` catalog, not a hardcoded option list — see
 * `lib/validation/staff.ts`'s own doc comment for why.
 */
export function CreateStaffSheet({ open, onOpenChange, onCreated }: CreateStaffSheetProps) {
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);

  const rolesQuery = useAssignableRoles();

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<StaffCreateFormValues>({
    resolver: zodResolver(staffCreateSchema),
    defaultValues: { name: "", email: "", password: "", roleCode: "" },
  });

  const mutation = useCreateStaff();

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
              setError(fieldError.field as keyof StaffCreateFormValues, {
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
          // StaffService only raises CONFLICT for a duplicate email within
          // the tenant, mirroring `create-student-sheet.tsx`'s identical
          // assumption.
          setError("email", { type: "server", message: error.message });
          return;
        }

        // A stale UI could still let a create request through even when
        // "Add staff" is hidden client-side — a real 403 must still be
        // handled gracefully here, not crash the form.
        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  const rolesUnavailable = rolesQuery.status === "error";
  const rolesLoading = rolesQuery.status === "pending";

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
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
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
      >
        <SheetHeader>
          <SheetTitle>Add staff</SheetTitle>
          <SheetDescription>
            Create a new staff account for this institute. They&apos;ll need to change their
            password on first sign-in.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4">
          {pageError ? (
            <ErrorState
              message={pageError.message}
              code={pageError.code}
              fieldErrors={pageError.fieldErrors}
              fieldLabels={FIELD_LABELS}
              onRetry={() => {
                setPageError(null);
                void onSubmit();
              }}
            />
          ) : null}

          {rolesUnavailable ? (
            <ErrorState
              message="Couldn't load the list of assignable roles."
              onRetry={() => void rolesQuery.refetch()}
            />
          ) : null}

          <form
            id="create-staff-form"
            className="flex flex-col gap-4"
            onSubmit={onSubmit}
            aria-busy={mutation.isPending}
            noValidate
          >
            <span role="status" aria-live="polite" className="sr-only">
              {mutation.isPending ? "Creating staff account…" : ""}
            </span>
            <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
              <legend className="sr-only">Add staff</legend>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="create-staff-name">Name</Label>
                <Input
                  id="create-staff-name"
                  type="text"
                  autoComplete="name"
                  aria-invalid={errors.name ? true : undefined}
                  aria-describedby={errors.name ? "create-staff-name-error" : undefined}
                  {...register("name")}
                />
                {errors.name ? (
                  <p id="create-staff-name-error" role="alert" className="text-xs text-destructive">
                    {errors.name.message}
                  </p>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="create-staff-email">Email</Label>
                <Input
                  id="create-staff-email"
                  type="email"
                  autoComplete="email"
                  aria-invalid={errors.email ? true : undefined}
                  aria-describedby={errors.email ? "create-staff-email-error" : undefined}
                  {...register("email")}
                />
                {errors.email ? (
                  <p id="create-staff-email-error" role="alert" className="text-xs text-destructive">
                    {errors.email.message}
                  </p>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="create-staff-password">Password</Label>
                <Input
                  id="create-staff-password"
                  type="password"
                  autoComplete="new-password"
                  aria-invalid={errors.password ? true : undefined}
                  aria-describedby={
                    [
                      "create-staff-password-helper",
                      errors.password ? "create-staff-password-error" : undefined,
                    ]
                      .filter(Boolean)
                      .join(" ") || undefined
                  }
                  {...register("password")}
                />
                <p id="create-staff-password-helper" className="text-xs text-muted-foreground">
                  At least 8 characters.
                </p>
                {errors.password ? (
                  <p id="create-staff-password-error" role="alert" className="text-xs text-destructive">
                    {errors.password.message}
                  </p>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="create-staff-role">Role</Label>
                <select
                  id="create-staff-role"
                  disabled={rolesLoading || rolesUnavailable}
                  aria-invalid={errors.roleCode ? true : undefined}
                  aria-describedby={errors.roleCode ? "create-staff-role-error" : undefined}
                  className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30"
                  {...register("roleCode")}
                >
                  <option value="">
                    {rolesLoading ? "Loading roles…" : "Select a role"}
                  </option>
                  {(rolesQuery.data ?? []).map((role) => (
                    <option key={role.code} value={role.code}>
                      {role.displayName}
                    </option>
                  ))}
                </select>
                {errors.roleCode ? (
                  <p id="create-staff-role-error" role="alert" className="text-xs text-destructive">
                    {errors.roleCode.message}
                  </p>
                ) : null}
              </div>
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
          <Button
            type="submit"
            form="create-staff-form"
            disabled={mutation.isPending || rolesUnavailable}
          >
            {mutation.isPending ? "Creating…" : "Create staff account"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
