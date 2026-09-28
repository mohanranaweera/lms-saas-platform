"use client";

import { ClipboardList } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ProfileFieldsForm } from "@/components/students/profile-fields-form";
import { useOwnProfileFields, useUpdateOwnProfileFields } from "@/lib/api/student-profile-fields";

/**
 * The student's own tenant-configured "Additional details" (STU-PROF-01) —
 * `GET/PUT /v1/students/me/profile`, no id anywhere. Which fields appear and
 * which are required is the institute's configuration, resolved by the
 * backend; required fields must be filled to save (backend-enforced too).
 */
export function AdditionalDetailsSection() {
  const query = useOwnProfileFields();
  const mutation = useUpdateOwnProfileFields();

  return (
    <section
      id="additional-details"
      aria-labelledby="additional-details-heading"
      className="flex max-w-3xl flex-col gap-4"
    >
      <div>
        <h2 id="additional-details-heading" className="text-lg font-medium text-foreground">
          Additional details
        </h2>
        <p className="text-sm text-muted-foreground">
          Information your institute collects about its students. Fields marked (optional) can be left blank.
        </p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your details…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.fields.length === 0}
        emptyState={{
          icon: <ClipboardList className="size-6" aria-hidden="true" />,
          title: "Nothing else to fill in",
          description: "Your institute doesn't collect any additional details at the moment.",
        }}
      >
        {(data) => (
          <ProfileFieldsForm
            fields={data.fields}
            idPrefix="my-details"
            enforceRequired
            save={(values) => mutation.mutateAsync(values)}
            savingLabel="Saving your details…"
            savedLabel="Details saved."
          />
        )}
      </QueryStateBoundary>
    </section>
  );
}
