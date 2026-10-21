"use client";

import { ClipboardList } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ProfileFieldValuesList } from "@/components/students/profile-field-inputs";
import { ProfileFieldsForm } from "@/components/students/profile-fields-form";
import { useStudentProfileFields, useUpdateStudentProfileFields } from "@/lib/api/student-profile-fields";

/**
 * Staff view/correction of a student's tenant-configured profile fields
 * (STU-PROF-01) — `GET/PUT /v1/students/{id}/profile-fields`, gated
 * server-side by `STUDENTS` VIEW/CREATE_EDIT. `canManage` only chooses the
 * editable vs read-only rendering; a stale UI still gets a handled 403.
 * Staff edits are not held to the student's required fields (backend rule).
 */
export function ProfileFieldsSection({ studentId, canManage }: { studentId: string; canManage: boolean }) {
  const query = useStudentProfileFields(studentId);
  const mutation = useUpdateStudentProfileFields(studentId);

  return (
    <section aria-labelledby="student-profile-fields-heading" className="flex max-w-3xl flex-col gap-4">
      <div>
        <h3 id="student-profile-fields-heading" className="text-base font-medium text-foreground">
          Additional details
        </h3>
        <p className="text-sm text-muted-foreground">
          The profile fields your institute collects. Configure them in Settings → Student fields.
        </p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading additional details…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.fields.length === 0}
        emptyState={{
          icon: <ClipboardList className="size-6" aria-hidden="true" />,
          title: "No additional fields enabled",
          description:
            "Your institute doesn't collect any additional student details yet. A Tenant Admin can enable fields in Settings → Student fields.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.missingRequiredFields.length > 0 ? (
              <p className="text-sm text-muted-foreground">
                {data.missingRequiredFields.length} required{" "}
                {data.missingRequiredFields.length === 1 ? "field is" : "fields are"} not filled in yet.
              </p>
            ) : null}
            {canManage ? (
              <ProfileFieldsForm
                fields={data.fields}
                idPrefix={`student-${studentId}-details`}
                enforceRequired={false}
                save={(values) => mutation.mutateAsync(values)}
                savingLabel="Saving the student's details…"
                savedLabel="Details saved."
              />
            ) : (
              <ProfileFieldValuesList fields={data.fields} />
            )}
          </div>
        )}
      </QueryStateBoundary>
    </section>
  );
}
