"use client";

import { useState } from "react";
import { useTeacherActivity } from "@/lib/api/audit-log";
import { ActivityTabContent } from "@/components/audit-log/activity-tab-content";

const PAGE_SIZE = 10;

/** Activity tab (Wave 3) — `GET /v1/teachers/{id}/activity`, paginated, append-only audit trail. */
export function TeacherActivityTab({ teacherId }: { teacherId: string }) {
  const [page, setPage] = useState(0);
  const query = useTeacherActivity(teacherId, { page, size: PAGE_SIZE });

  return (
    <ActivityTabContent
      query={query}
      page={page}
      onPageChange={setPage}
      emptyTitle="No activity recorded yet"
      emptyDescription="Privileged actions on this teacher's account (approval, suspension, and more) will appear here as they happen."
    />
  );
}
