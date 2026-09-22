import type { ReactNode } from "react";
import { Archive } from "lucide-react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { CoursePricingModelBadge } from "@/components/courses/course-pricing-model-badge";
import type { CourseResponse } from "@/lib/api/courses";

/** Archived indicator chip — never color alone, per `.claude/rules/ui-ux.md` §4. */
function ArchivedBadge() {
  return (
    <Badge variant="outline" className="gap-1">
      <Archive className="size-3" aria-hidden="true" />
      Archived
    </Badge>
  );
}

function PricingCell({ course }: { course: CourseResponse }) {
  return (
    <div className="flex flex-col items-start gap-1">
      <CoursePricingModelBadge pricingModel={course.pricingModel} />
      {course.pricingModel === "ONE_TIME" ? (
        <span className="text-xs text-muted-foreground">{course.price.toFixed(2)}</span>
      ) : null}
    </div>
  );
}

/**
 * Responsive course list — table on `md` and above, card list below `md`,
 * per `.claude/rules/ui-ux.md` §5. Shared between Teacher "My Courses" and
 * Tenant Admin's Course List (both render `CourseResponse[]` rows; only the
 * per-row actions and whether the owning-teacher column is shown differ).
 *
 * Wave 2: the "Price" column became "Pricing" (pricing-model badge, plus the
 * flat price only when it's actually meaningful — `ONE_TIME`) and every
 * archived row (`archivedAt != null`) carries a visible "Archived" chip next
 * to its status, on both the desktop table and the mobile card.
 */
export function CourseListTable({
  courses,
  renderActions,
  showTeacherColumn = false,
}: {
  courses: CourseResponse[];
  renderActions: (course: CourseResponse) => ReactNode;
  /** Tenant Admin needs a way to identify/filter by owning teacher; Teacher's own list never needs this (every row is already their own course). */
  showTeacherColumn?: boolean;
}) {
  return (
    <>
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Course name</TableHead>
              <TableHead>Category</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Pricing</TableHead>
              {showTeacherColumn ? <TableHead>Teacher</TableHead> : null}
              <TableHead>
                <span className="sr-only">Actions</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {courses.map((course) => (
              <TableRow key={course.id}>
                <TableCell className="font-medium whitespace-normal text-foreground">
                  {course.name}
                  <div className="text-xs font-normal text-muted-foreground">{course.slug}</div>
                </TableCell>
                <TableCell className="text-muted-foreground">{course.category}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <CourseStatusBadge status={course.status} />
                    {course.archivedAt ? <ArchivedBadge /> : null}
                  </div>
                </TableCell>
                <TableCell className="text-muted-foreground">
                  <PricingCell course={course} />
                </TableCell>
                {showTeacherColumn ? (
                  <TableCell
                    className="font-mono text-xs text-muted-foreground"
                    title={course.teacherId}
                  >
                    <span aria-hidden="true">{course.teacherId.slice(0, 8)}&hellip;</span>
                    <span className="sr-only">{course.teacherId}</span>
                  </TableCell>
                ) : null}
                <TableCell>
                  <div className="flex flex-wrap gap-2">{renderActions(course)}</div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden">
        {courses.map((course) => (
          <li key={course.id} className="flex flex-col gap-3 rounded-lg border border-border p-4">
            <div className="flex items-start justify-between gap-2">
              <div>
                <p className="font-medium text-foreground">{course.name}</p>
                <p className="text-xs text-muted-foreground">{course.slug}</p>
              </div>
              <div className="flex flex-col items-end gap-1.5">
                <CourseStatusBadge status={course.status} />
                {course.archivedAt ? <ArchivedBadge /> : null}
              </div>
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <dt className="font-medium text-foreground">Category</dt>
              <dd>{course.category}</dd>
              <dt className="font-medium text-foreground">Pricing</dt>
              <dd>
                <PricingCell course={course} />
              </dd>
              {showTeacherColumn ? (
                <>
                  <dt className="font-medium text-foreground">Teacher</dt>
                  <dd className="font-mono" title={course.teacherId}>
                    <span aria-hidden="true">{course.teacherId.slice(0, 8)}&hellip;</span>
                    <span className="sr-only">{course.teacherId}</span>
                  </dd>
                </>
              ) : null}
            </dl>
            <div className="flex flex-wrap gap-2">{renderActions(course)}</div>
          </li>
        ))}
      </ul>
    </>
  );
}
