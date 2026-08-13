"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useOwnStudentProfile, useUpdateOwnStudentProfile } from "@/lib/api/students";
import { studentUpdateSchema, type StudentUpdateFormValues } from "@/lib/validation/students";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import type { StudentResponse } from "@/lib/api/students";

/**
 * My Profile (MVP-006, Student self-service). Reads/writes
 * `GET`/`PATCH /v1/students/me` only — deliberately no id anywhere in this
 * page's code or URL (`/student/profile`, no `[id]` segment), so viewing or
 * editing another student's data by URL manipulation is structurally
 * impossible from this page, per the module plan's security requirement.
 */
export default function StudentProfilePage() {
  const profileQuery = useOwnStudentProfile();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">My Profile</h1>
        <p className="text-sm text-muted-foreground">
          View and update your own account details.
        </p>
      </div>

      <QueryStateBoundary
        query={profileQuery}
        loadingLabel="Loading your profile…"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        loginPath="/login"
      >
        {(profile) => <ProfileForm profile={profile} />}
      </QueryStateBoundary>
    </div>
  );
}

function ProfileForm({ profile }: { profile: StudentResponse }) {
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);

  const updateMutation = useUpdateOwnStudentProfile();

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<StudentUpdateFormValues>({
    resolver: zodResolver(studentUpdateSchema),
    defaultValues: { name: profile.name },
  });

  useEffect(() => {
    reset({ name: profile.name });
  }, [profile.name, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await updateMutation.mutateAsync(values);
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter((fieldError) => fieldError.field !== "name");
          for (const fieldError of error.fieldErrors) {
            if (fieldError.field === "name") {
              setError("name", { type: "server", message: fieldError.message });
            }
          }
          // `name` is the only editable field this form renders — a field
          // error for anything else would otherwise be silently dropped,
          // leaving no visible feedback at all. Mirrors
          // create-student-sheet.tsx's unmapped-field handling.
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
            });
          }
          return;
        }
        setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-medium text-foreground">{profile.name}</h2>
        <StudentStatusBadge status={profile.status} />
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
        <dt className="font-medium text-muted-foreground">Email</dt>
        <dd className="text-foreground">{profile.email}</dd>
        <dt className="font-medium text-muted-foreground">Role</dt>
        <dd className="text-foreground">{profile.roleCode}</dd>
      </dl>

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          // A failed *submission*, not a failed load — retry means
          // resubmitting the form's current (still-populated) values, not
          // just dismissing the banner. See create-student-sheet.tsx's
          // identical reasoning.
          onRetry={() => {
            setPageError(null);
            void onSubmit();
          }}
        />
      ) : null}

      <form
        className="flex flex-col gap-4"
        onSubmit={onSubmit}
        aria-busy={updateMutation.isPending}
        noValidate
      >
        <span role="status" aria-live="polite" className="sr-only">
          {updateMutation.isPending ? "Saving…" : saved ? "Saved." : ""}
        </span>
        <fieldset disabled={updateMutation.isPending} className="flex flex-col gap-4">
          <legend className="sr-only">Edit my profile</legend>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="profile-name">Name</Label>
            <Input
              id="profile-name"
              type="text"
              autoComplete="name"
              aria-invalid={errors.name ? true : undefined}
              aria-describedby={errors.name ? "profile-name-error" : undefined}
              {...register("name")}
            />
            {errors.name ? (
              <p id="profile-name-error" role="alert" className="text-xs text-destructive">
                {errors.name.message}
              </p>
            ) : null}
          </div>
        </fieldset>
        <div className="flex items-center gap-3">
          <Button type="submit" disabled={updateMutation.isPending || !isDirty}>
            {updateMutation.isPending ? "Saving…" : "Save changes"}
          </Button>
          {saved && !isDirty ? (
            <span role="status" className="text-sm text-muted-foreground">
              Saved.
            </span>
          ) : null}
        </div>
      </form>
    </div>
  );
}
