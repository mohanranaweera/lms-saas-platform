"use client";

import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StudentAvatar } from "@/components/students/student-avatar";
import { isApiClientError } from "@/lib/api/error";
import {
  useOwnTeacherProfile,
  useUpdateOwnTeacherProfile,
  type TeacherSelfProfile,
} from "@/lib/api/teachers";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";

function profileSchema(t: TranslateFn) {
  return z.object({
    qualifications: z.string().max(2000, t("teacher.profile.validation.qualificationsTooLong", { max: "2000" })),
    description: z.string().max(5000, t("teacher.profile.validation.biographyTooLong", { max: "5000" })),
  });
}
type FormValues = z.infer<ReturnType<typeof profileSchema>>;

/**
 * Teacher Portal → My Profile (TP-03). `GET/PATCH /v1/teachers/me` resolves
 * the record from the signed-in identity in the active tenant. Only the
 * server's `editableFields` allowlist (qualifications, biography) is
 * editable; names, contact, sign-in email, photo, role and commission are
 * managed by the institute and shown read-only (or not at all).
 */
export default function TeacherProfilePage() {
  const query = useOwnTeacherProfile();
  const t = useT();
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold break-words text-foreground">{t("teacher.profile.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("teacher.profile.subtitle")}</p>
      </div>
      {notFound ? (
        <div data-testid="teacher-profile-unavailable">
          <EmptyState
            title={t("teacher.profile.unavailable.title")}
            description={t("teacher.profile.unavailable.description")}
          />
        </div>
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel={t("teacher.profile.loading")}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        >
          {(profile) => <ProfileContent profile={profile} />}
        </QueryStateBoundary>
      )}
    </div>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words text-foreground">{value && value.trim() ? value : "—"}</dd>
    </div>
  );
}

function ProfileContent({ profile }: { profile: TeacherSelfProfile }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-6" data-testid="teacher-profile">
      <section className="flex flex-col gap-4 rounded-xl border border-border bg-card p-5 shadow-sm sm:flex-row sm:items-center">
        <StudentAvatar name={profile.name} photoUrl={profile.profileImageUrl} size="lg" />
        <div className="min-w-0">
          <p className="text-lg font-semibold break-words text-foreground" data-testid="teacher-profile-name">
            {profile.name}
          </p>
          <p className="text-sm break-words text-muted-foreground">{profile.email}</p>
        </div>
      </section>

      <section className="rounded-xl border border-border bg-card p-5 shadow-sm" aria-labelledby="teacher-profile-details">
        <h2 id="teacher-profile-details" className="text-base font-semibold text-foreground">
          {t("teacher.profile.details.heading")}
        </h2>
        <p className="mb-4 text-xs text-muted-foreground">{t("teacher.profile.details.managed")}</p>
        <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <ReadOnlyField label={t("teacher.profile.details.firstName")} value={profile.firstName} />
          <ReadOnlyField label={t("teacher.profile.details.lastName")} value={profile.lastName} />
          <ReadOnlyField label={t("teacher.profile.details.email")} value={profile.email} />
          <ReadOnlyField label={t("teacher.profile.details.contactNumber")} value={profile.contactNumber} />
        </dl>
      </section>

      <ProfessionalForm profile={profile} />
    </div>
  );
}

function ProfessionalForm({ profile }: { profile: TeacherSelfProfile }) {
  const update = useUpdateOwnTeacherProfile();
  const t = useT();
  const schema = useMemo(() => profileSchema(t), [t]);
  const [saved, setSaved] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const editable = new Set<string>(profile.editableFields);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { qualifications: profile.qualifications ?? "", description: profile.description ?? "" },
  });
  const { reset } = form;

  useEffect(() => {
    reset({ qualifications: profile.qualifications ?? "", description: profile.description ?? "" });
  }, [reset, profile.qualifications, profile.description]);

  const canEdit = editable.has("qualifications") || editable.has("description");

  const onSubmit = form.handleSubmit(async (values) => {
    setSaved(false);
    setServerError(null);
    try {
      await update.mutateAsync(values);
      setSaved(true);
    } catch (err) {
      setServerError(
        isApiClientError(err) && err.status === 400
          ? t("teacher.profile.saveInvalid")
          : isApiClientError(err) && err.code === "NETWORK_ERROR"
            ? localizeApiError(t, err)
            : t("teacher.profile.saveFailed")
      );
    }
  });

  const errors = form.formState.errors;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-5 shadow-sm"
      aria-labelledby="teacher-profile-professional"
    >
      <h2 id="teacher-profile-professional" className="text-base font-semibold text-foreground">
        {t("teacher.profile.professional.heading")}
      </h2>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="teacher-profile-qualifications">{t("teacher.profile.professional.qualifications")}</Label>
        <Textarea
          id="teacher-profile-qualifications"
          rows={3}
          readOnly={!editable.has("qualifications")}
          aria-invalid={errors.qualifications ? true : undefined}
          aria-describedby={errors.qualifications ? "teacher-profile-qualifications-error" : undefined}
          {...form.register("qualifications")}
        />
        {errors.qualifications ? (
          <p id="teacher-profile-qualifications-error" className="text-sm text-destructive">
            {errors.qualifications.message}
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="teacher-profile-description">{t("teacher.profile.professional.biography")}</Label>
        <Textarea
          id="teacher-profile-description"
          rows={6}
          readOnly={!editable.has("description")}
          aria-invalid={errors.description ? true : undefined}
          aria-describedby={errors.description ? "teacher-profile-description-error" : undefined}
          {...form.register("description")}
        />
        {errors.description ? (
          <p id="teacher-profile-description-error" className="text-sm text-destructive">
            {errors.description.message}
          </p>
        ) : null}
      </div>
      {saved ? (
        <Alert role="status" data-testid="teacher-profile-saved">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{t("teacher.profile.saved")}</AlertDescription>
        </Alert>
      ) : null}
      {serverError ? (
        <p role="alert" className="text-sm text-destructive" data-testid="teacher-profile-error">
          {serverError}
        </p>
      ) : null}
      {canEdit ? (
        <div>
          <Button type="submit" disabled={update.isPending} aria-busy={update.isPending} data-testid="teacher-profile-save">
            {update.isPending ? t("teacher.profile.saving") : t("teacher.profile.save")}
          </Button>
        </div>
      ) : null}
    </form>
  );
}
