"use client";

import { useMemo, type ReactNode } from "react";
import { useCourses } from "@/lib/api/courses";
import { useStudentPhoto, type StudentDetail } from "@/lib/api/student-management";
import { shortId } from "@/lib/format";

/**
 * Course id → name lookup for enrolled-course lists. Falls back to a short id
 * when the caller can't list courses (e.g. a role without `COURSES`/`VIEW`).
 */
export function useCourseNames(): (courseId: string) => string {
  const coursesQuery = useCourses({ size: 100 });
  const names = useMemo(
    () => new Map((coursesQuery.data?.content ?? []).map((course) => [course.id, course.name])),
    [coursesQuery.data]
  );
  return (courseId: string) => names.get(courseId) ?? shortId(courseId);
}

/** The student's signed photo URL: from the detail payload when present, else `GET /students/{id}/photo`. */
export function useStudentPhotoUrl(detail: StudentDetail | undefined, studentId: string): string | null {
  const inline = detail?.photoUrl;
  const query = useStudentPhoto(
    { kind: "staff", studentId },
    { enabled: Boolean(detail) && inline === undefined && studentId.length > 0 }
  );
  if (inline !== undefined) return inline ?? null;
  return query.data?.url ?? null;
}

/** "Label / value" pair used by the drawer and the profile sidebar. Empty values render an em dash. */
export function InfoItem({
  icon,
  label,
  value,
  testId,
}: {
  icon?: ReactNode;
  label: string;
  value: ReactNode;
  testId?: string;
}) {
  const empty = value === null || value === undefined || value === "";
  return (
    <div className="flex items-start gap-3" data-testid={testId}>
      {icon ? <span className="mt-0.5 text-muted-foreground [&_svg]:size-4">{icon}</span> : null}
      {/* Label first in reading order; shown value-first like Klass. */}
      <div className="flex min-w-0 flex-col-reverse">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="break-words text-sm text-foreground">{empty ? "—" : value}</p>
      </div>
    </div>
  );
}

export function guardianSummary(detail: StudentDetail): string | null {
  const parts = [detail.guardianName, detail.guardianPhone, detail.guardianNic].filter(
    (part): part is string => Boolean(part && part.trim())
  );
  return parts.length ? parts.join(" · ") : null;
}

export function locationSummary(detail: StudentDetail): string | null {
  const parts = [detail.addressLine, detail.addressLine2, detail.city, detail.postalCode].filter(
    (part): part is string => Boolean(part && part.trim())
  );
  return parts.length ? parts.join(", ") : null;
}
