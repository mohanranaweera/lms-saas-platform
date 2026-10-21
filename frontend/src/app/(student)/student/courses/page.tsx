import { Suspense } from "react";
import { LoadingState } from "@/components/states/loading-state";
import { StudentCourseCatalog } from "@/components/courses/student/student-course-catalog";

/**
 * Student "My Courses" - Klass course discovery (KLASS-T5b, CM-S01, EP-E01..E05).
 * `?view=available` shows Available Courses; the default is Enrolled Courses.
 * Enrollment here is the free, server-confirmed participation of ADR-028; it
 * never goes through checkout. Legacy paid-access renewal stays on
 * `/student/payments/reactivation` (nav "Reactivation").
 *
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached route
 * entry - with its canonical URL - for both `/student/courses` and
 * `/student/courses?view=available` (the two nav links), so navigating from
 * Available to "My Courses" (or the old `/student/browse` redirect followed by
 * the Enrolled pill) wrote `?view=available` back and never switched view.
 * The view itself is still derived client-side from the URL by the catalog.
 */
export default async function StudentMyCoursesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return (
    <Suspense fallback={<LoadingState label="Loading courses…" />}>
      <StudentCourseCatalog />
    </Suspense>
  );
}
