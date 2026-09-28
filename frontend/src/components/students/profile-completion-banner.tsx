"use client";

import Link from "next/link";
import { AlertTriangle } from "lucide-react";
import { useOwnProfileFields } from "@/lib/api/student-profile-fields";

/**
 * "Complete your profile" notice for a student whose institute requires
 * profile details they haven't filled in yet (STU-PROF-01). Driven solely by
 * the backend's `missingRequiredFields`; renders nothing while loading, on
 * error, or when nothing is missing. Informational only — missing details
 * never block anything (owner decision 3).
 */
export function ProfileCompletionBanner({ showLink = true }: { showLink?: boolean }) {
  const query = useOwnProfileFields();
  if (query.status !== "success" || query.data.missingRequiredFields.length === 0) {
    return null;
  }

  const missing = new Set(query.data.missingRequiredFields);
  const labels = query.data.fields.filter((field) => missing.has(field.key)).map((field) => field.label);

  return (
    <div
      role="status"
      className="flex flex-col gap-2 rounded-md border border-border bg-muted/40 px-3 py-3 text-sm text-foreground sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <p>
          <span className="font-medium">Complete your profile.</span> Your institute asks for:{" "}
          {labels.join(", ")}.
        </p>
      </div>
      {showLink ? (
        <Link
          href="/student/profile#additional-details"
          className="shrink-0 font-medium text-foreground underline-offset-4 hover:underline"
        >
          Complete profile
        </Link>
      ) : null}
    </div>
  );
}
