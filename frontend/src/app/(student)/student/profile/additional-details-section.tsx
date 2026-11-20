"use client";

import { ClipboardList } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ProfileFieldsForm } from "@/components/students/profile-fields-form";
import { useOwnProfileFields, useUpdateOwnProfileFields } from "@/lib/api/student-profile-fields";
import { useT } from "@/i18n/i18n-provider";

/**
 * The student's own tenant-configured "Additional details" (STU-PROF-01) —
 * `GET/PUT /v1/students/me/profile`, no id anywhere. Which fields appear and
 * which are required is the institute's configuration, resolved by the
 * backend; required fields must be filled to save (backend-enforced too).
 */
export function AdditionalDetailsSection() {
  const query = useOwnProfileFields();
  const mutation = useUpdateOwnProfileFields();
  const t = useT();

  return (
    <section
      id="additional-details"
      aria-labelledby="additional-details-heading"
      className="flex max-w-3xl flex-col gap-4"
    >
      <div>
        <h2 id="additional-details-heading" className="text-lg font-medium text-foreground">
          {t("student.profile.additional.heading")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("student.profile.additional.description")}</p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("student.profile.additional.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.fields.length === 0}
        emptyState={{
          icon: <ClipboardList className="size-6" aria-hidden="true" />,
          title: t("student.profile.additional.empty.title"),
          description: t("student.profile.additional.empty.description"),
        }}
      >
        {(data) => (
          <ProfileFieldsForm
            fields={data.fields}
            idPrefix="my-details"
            enforceRequired
            save={(values) => mutation.mutateAsync(values)}
            savingLabel={t("student.profile.additional.saving")}
            savedLabel={t("student.profile.additional.saved")}
          />
        )}
      </QueryStateBoundary>
    </section>
  );
}
