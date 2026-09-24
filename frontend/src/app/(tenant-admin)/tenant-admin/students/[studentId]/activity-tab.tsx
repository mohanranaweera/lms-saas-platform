"use client";

import { useState } from "react";
import { useStudentActivity } from "@/lib/api/audit-log";
import { ActivityTabContent } from "@/components/audit-log/activity-tab-content";

const PAGE_SIZE = 10;

/** Activity tab (Wave 3) — `GET /v1/students/{id}/activity`, paginated, append-only audit trail. */
export function StudentActivityTab({ studentId }: { studentId: string }) {
  const [page, setPage] = useState(0);
  const query = useStudentActivity(studentId, { page, size: PAGE_SIZE });

  return (
    <ActivityTabContent
      query={query}
      page={page}
      onPageChange={setPage}
      emptyTitle="No activity recorded yet"
      emptyDescription="Privileged actions on this student's account (activation, enrollment, password resets, and more) will appear here as they happen."
    />
  );
}
