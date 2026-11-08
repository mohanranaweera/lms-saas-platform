"use client";

import { useState, type ReactNode } from "react";
import { useForm, type FieldValues, type Path, type UseFormSetError } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PlusCircle, Pencil, UserCog } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { StaffFormDialog } from "@/components/staff/staff-form-dialog";
import { isApiClientError } from "@/lib/api/error";
import {
  useAssignableStaffRoles,
  useChangeStaffRole,
  useCreateStaff,
  useStaffLifecycle,
  useUpdateStaff,
  type StaffResponse,
} from "@/lib/api/staff";
import {
  staffCreateSchema,
  staffRoleSchema,
  staffUpdateSchema,
  type StaffCreateFormValues,
  type StaffRoleFormValues,
  type StaffUpdateFormValues,
} from "@/lib/validation/staff";

const SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive dark:bg-input/30";

export function staffDisplayName(staff: Pick<StaffResponse, "firstName" | "lastName" | "name" | "email">): string {
  const joined = [staff.firstName, staff.lastName].filter(Boolean).join(" ").trim();
  return joined || staff.name || staff.email;
}

/**
 * Maps server field errors onto the form; returns the form-level message to
 * show for anything else (409 conflict, 403, unexpected).
 */
function applyServerError<T extends FieldValues>(
  error: unknown,
  knownFields: readonly string[],
  setError: UseFormSetError<T>,
  fallback: string
): string | null {
  if (!isApiClientError(error)) return fallback;
  const mapped = error.fieldErrors.filter((fieldError) => knownFields.includes(fieldError.field));
  for (const fieldError of mapped) {
    setError(fieldError.field as Path<T>, { type: "server", message: fieldError.message });
  }
  if (mapped.length > 0 && mapped.length === error.fieldErrors.length) return null;
  if (error.status === 409) {
    return error.message || "A staff member with this email already exists in this institute.";
  }
  if (error.status === 403) {
    return "You no longer have permission to do this. Ask a Tenant Admin if you need access.";
  }
  return error.message || fallback;
}

function Field({
  id,
  label,
  error,
  helper,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  helper?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>
        {label}
        <span aria-hidden="true" className="text-destructive">
          {" "}
          *
        </span>
      </Label>
      {children}
      {helper ? (
        <p id={`${id}-helper`} className="text-xs text-muted-foreground">
          {helper}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function describedBy(id: string, error: unknown, helper = false): string | undefined {
  return [helper ? `${id}-helper` : null, error ? `${id}-error` : null].filter(Boolean).join(" ") || undefined;
}

function FormError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <Alert variant="destructive" className="mb-4" data-testid="staff-form-error">
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}

/** Klass "Add New User": invites an Operator / Manager. No password field - the invitee sets it from the email. */
export function AddUserDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (staff: StaffResponse) => void;
}) {
  const rolesQuery = useAssignableStaffRoles({ enabled: open });
  const mutation = useCreateStaff();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<StaffCreateFormValues>({
    resolver: zodResolver(staffCreateSchema),
    defaultValues: { firstName: "", lastName: "", email: "", mobileNumber: "", roleCode: "" },
  });

  const close = (next: boolean) => {
    if (!next && mutation.isPending) return;
    if (!next) {
      reset();
      setFormError(null);
    }
    onOpenChange(next);
  };

  const onSubmit = handleSubmit(async (values) => {
    if (mutation.isPending) return;
    setFormError(null);
    try {
      const created = await mutation.mutateAsync(values);
      reset();
      onCreated(created);
      onOpenChange(false);
    } catch (error) {
      setFormError(
        applyServerError(
          error,
          ["firstName", "lastName", "email", "mobileNumber", "roleCode"],
          setError,
          "The user couldn't be created. Please try again."
        )
      );
    }
  });

  const rolesLoading = rolesQuery.status === "pending";
  const rolesFailed = rolesQuery.status === "error";

  return (
    <StaffFormDialog
      open={open}
      onOpenChange={close}
      title="Add New User"
      icon={<PlusCircle className="size-5 text-primary" aria-hidden="true" />}
      description="They'll receive an email with a link to set their own password."
      testId="add-user-dialog"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => close(false)}>
            Cancel
          </Button>
          <Button type="submit" form="add-user-form" disabled={mutation.isPending || rolesFailed}>
            {mutation.isPending ? "Creating…" : "Create User"}
          </Button>
        </>
      }
    >
      <FormError message={formError} />
      {rolesFailed ? (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>
            Couldn&apos;t load the roles you can assign.{" "}
            <Button type="button" variant="link" className="h-auto p-0" onClick={() => void rolesQuery.refetch()}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      <form id="add-user-form" noValidate onSubmit={onSubmit} aria-busy={mutation.isPending}>
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Creating user…" : ""}
        </span>
        <fieldset disabled={mutation.isPending} className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <legend className="sr-only">New user details</legend>
          <Field id="add-user-first-name" label="First Name" error={errors.firstName?.message}>
            <Input
              id="add-user-first-name"
              autoComplete="given-name"
              aria-required="true"
              aria-invalid={errors.firstName ? true : undefined}
              aria-describedby={describedBy("add-user-first-name", errors.firstName)}
              {...register("firstName")}
            />
          </Field>
          <Field id="add-user-last-name" label="Last Name" error={errors.lastName?.message}>
            <Input
              id="add-user-last-name"
              autoComplete="family-name"
              aria-required="true"
              aria-invalid={errors.lastName ? true : undefined}
              aria-describedby={describedBy("add-user-last-name", errors.lastName)}
              {...register("lastName")}
            />
          </Field>
          <Field id="add-user-email" label="Email" error={errors.email?.message}>
            <Input
              id="add-user-email"
              type="email"
              autoComplete="email"
              aria-required="true"
              aria-invalid={errors.email ? true : undefined}
              aria-describedby={describedBy("add-user-email", errors.email)}
              {...register("email")}
            />
          </Field>
          <Field id="add-user-mobile" label="Mobile Number" error={errors.mobileNumber?.message}>
            <Input
              id="add-user-mobile"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              aria-required="true"
              aria-invalid={errors.mobileNumber ? true : undefined}
              aria-describedby={describedBy("add-user-mobile", errors.mobileNumber)}
              {...register("mobileNumber")}
            />
          </Field>
          <Field id="add-user-role" label="Role" error={errors.roleCode?.message}>
            <select
              id="add-user-role"
              className={SELECT_CLASS}
              disabled={rolesLoading || rolesFailed}
              aria-required="true"
              aria-invalid={errors.roleCode ? true : undefined}
              aria-describedby={describedBy("add-user-role", errors.roleCode)}
              {...register("roleCode")}
            >
              <option value="">{rolesLoading ? "Loading roles…" : "Select a role"}</option>
              {(rolesQuery.data ?? []).map((role) => (
                <option key={role.code} value={role.code}>
                  {role.displayName}
                </option>
              ))}
            </select>
          </Field>
        </fieldset>
      </form>
    </StaffFormDialog>
  );
}

/** Edit a staff member's name and contact number (`PATCH /v1/staff/{id}`). */
export function EditStaffDialog({
  staff,
  onOpenChange,
  onSaved,
}: {
  staff: StaffResponse;
  onOpenChange: (open: boolean) => void;
  onSaved: (message: string) => void;
}) {
  const mutation = useUpdateStaff();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<StaffUpdateFormValues>({
    resolver: zodResolver(staffUpdateSchema),
    defaultValues: {
      firstName: staff.firstName ?? "",
      lastName: staff.lastName ?? "",
      contactNumber: staff.contactNumber ?? "",
    },
  });

  const onSubmit = handleSubmit(async (values) => {
    if (mutation.isPending) return;
    setFormError(null);
    try {
      const updated = await mutation.mutateAsync({ id: staff.id, input: values });
      onSaved(`${staffDisplayName(updated)}'s details were updated.`);
      onOpenChange(false);
    } catch (error) {
      setFormError(
        applyServerError(
          error,
          ["firstName", "lastName", "contactNumber"],
          setError,
          "The details couldn't be saved. Please try again."
        )
      );
    }
  });

  return (
    <StaffFormDialog
      open
      onOpenChange={(next) => (!next && mutation.isPending ? undefined : onOpenChange(next))}
      title="Edit details"
      icon={<Pencil className="size-5 text-primary" aria-hidden="true" />}
      description={staff.email}
      testId="edit-staff-dialog"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="edit-staff-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Save"}
          </Button>
        </>
      }
    >
      <FormError message={formError} />
      <form id="edit-staff-form" noValidate onSubmit={onSubmit} aria-busy={mutation.isPending}>
        <fieldset disabled={mutation.isPending} className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <legend className="sr-only">Staff details</legend>
          <Field id="edit-staff-first-name" label="First Name" error={errors.firstName?.message}>
            <Input
              id="edit-staff-first-name"
              autoComplete="given-name"
              aria-required="true"
              aria-invalid={errors.firstName ? true : undefined}
              aria-describedby={describedBy("edit-staff-first-name", errors.firstName)}
              {...register("firstName")}
            />
          </Field>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="edit-staff-last-name">Last Name</Label>
            <Input
              id="edit-staff-last-name"
              autoComplete="family-name"
              aria-invalid={errors.lastName ? true : undefined}
              aria-describedby={errors.lastName ? "edit-staff-last-name-error" : undefined}
              {...register("lastName")}
            />
            {errors.lastName ? (
              <p id="edit-staff-last-name-error" role="alert" className="text-xs text-destructive">
                {errors.lastName.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="edit-staff-contact">Contact Number</Label>
            <Input
              id="edit-staff-contact"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              aria-invalid={errors.contactNumber ? true : undefined}
              aria-describedby={errors.contactNumber ? "edit-staff-contact-error" : undefined}
              {...register("contactNumber")}
            />
            {errors.contactNumber ? (
              <p id="edit-staff-contact-error" role="alert" className="text-xs text-destructive">
                {errors.contactNumber.message}
              </p>
            ) : null}
          </div>
        </fieldset>
      </form>
    </StaffFormDialog>
  );
}

/** Change a staff member's role (`PUT /v1/staff/{id}/role`); options are the server's assignable set. */
export function ChangeRoleDialog({
  staff,
  onOpenChange,
  onSaved,
}: {
  staff: StaffResponse;
  onOpenChange: (open: boolean) => void;
  onSaved: (message: string) => void;
}) {
  const rolesQuery = useAssignableStaffRoles();
  const mutation = useChangeStaffRole();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<StaffRoleFormValues>({
    resolver: zodResolver(staffRoleSchema),
    defaultValues: { roleCode: staff.roleCode },
  });

  const onSubmit = handleSubmit(async (values) => {
    if (mutation.isPending) return;
    setFormError(null);
    try {
      const updated = await mutation.mutateAsync({ id: staff.id, roleCode: values.roleCode });
      onSaved(`${staffDisplayName(updated)} is now ${updated.roleName ?? updated.roleCode}.`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerError(error, ["roleCode"], setError, "The role couldn't be changed. Please try again."));
    }
  });

  const roles = rolesQuery.data ?? [];
  const currentIsAssignable = roles.some((role) => role.code === staff.roleCode);

  return (
    <StaffFormDialog
      open
      onOpenChange={(next) => (!next && mutation.isPending ? undefined : onOpenChange(next))}
      title="Change role"
      icon={<UserCog className="size-5 text-primary" aria-hidden="true" />}
      description={`${staffDisplayName(staff)} (${staff.email})`}
      testId="change-role-dialog"
      className="sm:max-w-md"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="submit"
            form="change-role-form"
            disabled={mutation.isPending || rolesQuery.status !== "success"}
          >
            {mutation.isPending ? "Saving…" : "Change role"}
          </Button>
        </>
      }
    >
      <FormError message={formError} />
      {rolesQuery.status === "error" ? (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>Couldn&apos;t load the roles you can assign.</AlertDescription>
        </Alert>
      ) : null}
      <form id="change-role-form" noValidate onSubmit={onSubmit}>
        <Field id="change-role-select" label="Role" error={errors.roleCode?.message}>
          <select
            id="change-role-select"
            className={SELECT_CLASS}
            disabled={mutation.isPending || rolesQuery.status !== "success"}
            aria-invalid={errors.roleCode ? true : undefined}
            aria-describedby={describedBy("change-role-select", errors.roleCode)}
            {...register("roleCode")}
          >
            {!currentIsAssignable ? (
              <option value={staff.roleCode} disabled>
                {staff.roleName ?? staff.roleCode} (current)
              </option>
            ) : null}
            {roles.map((role) => (
              <option key={role.code} value={role.code}>
                {role.displayName}
              </option>
            ))}
          </select>
        </Field>
      </form>
    </StaffFormDialog>
  );
}

/** Confirmation before deactivating (revokes the member's sessions server-side). */
export function DeactivateStaffDialog({
  staff,
  onOpenChange,
  onDone,
}: {
  staff: StaffResponse;
  onOpenChange: (open: boolean) => void;
  onDone: (message: string) => void;
}) {
  const mutation = useStaffLifecycle();
  const [error, setError] = useState<string | null>(null);
  const name = staffDisplayName(staff);

  const confirm = async () => {
    if (mutation.isPending) return;
    setError(null);
    try {
      await mutation.mutateAsync({ id: staff.id, action: "deactivate" });
      onDone(`${name} was deactivated.`);
      onOpenChange(false);
    } catch (caught) {
      setError(isApiClientError(caught) ? caught.message : "The user couldn't be deactivated. Please try again.");
    }
  };

  return (
    <AlertDialog open onOpenChange={(next) => (!next && mutation.isPending ? undefined : onOpenChange(next))}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Deactivate {name}?</AlertDialogTitle>
          <AlertDialogDescription>
            They will be signed out everywhere and can&apos;t sign in to this institute until reactivated. Their
            history is kept.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {error ? (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="button" variant="destructive" disabled={mutation.isPending} onClick={() => void confirm()}>
            {mutation.isPending ? "Deactivating…" : "Deactivate"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
