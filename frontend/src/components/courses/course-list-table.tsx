import type { ReactNode } from "react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import type { CourseResponse } from "@/lib/api/courses";

/**
 * Responsive course list — table on `md` and above, card list below `md`,
 * per `.claude/rules/ui-ux.md` §5. Shared between Teacher "My Courses" and
 * Tenant Admin's Course List (both render `CourseResponse[]` rows; only the
 * per-row actions and whether the owning-teacher column is shown differ).
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
              <TableHead>Price</TableHead>
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
                  <CourseStatusBadge status={course.status} />
                </TableCell>
                <TableCell className="text-muted-foreground">{course.price.toFixed(2)}</TableCell>
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
              <CourseStatusBadge status={course.status} />
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <dt className="font-medium text-foreground">Category</dt>
              <dd>{course.category}</dd>
              <dt className="font-medium text-foreground">Price</dt>
              <dd>{course.price.toFixed(2)}</dd>
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
