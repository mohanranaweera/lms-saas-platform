"use client";

import { TriangleAlert } from "lucide-react";
import { useCoursePaymentReadiness } from "@/lib/api/course-billing";

/**
 * Amber "Students can't pay for this course yet" warning for the course
 * workspace. Shown only once the backend's payment-readiness check
 * (`GET /v1/courses/{id}/payment-readiness`, which mirrors checkout's offer
 * rules) says `payable: false`. Purely advisory: hidden while loading, and a
 * failed readiness request hides it too - it never blocks the page.
 */
export function CoursePaymentReadinessPanel({ courseId }: { courseId: string }) {
  const query = useCoursePaymentReadiness(courseId);
  const readiness = query.data;
  // Only an explicit `payable: false` shows the panel - never a loading, failed or unexpected response.
  if (query.isError || readiness?.payable !== false) {
    return null;
  }
  const issues = Array.isArray(readiness.issues) ? readiness.issues : [];

  return (
    <section
      role="status"
      aria-labelledby="course-payment-readiness-title"
      data-testid="course-payment-readiness"
      className="flex gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950/50 dark:text-amber-100"
    >
      <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
      <div className="flex min-w-0 flex-col gap-1">
        <h2 id="course-payment-readiness-title" className="font-medium">
          Students can&apos;t pay for this course yet
        </h2>
        {issues.length > 0 ? (
          <ul className="list-disc space-y-1 pl-4">
            {issues.map((issue) => (
              <li key={issue.code}>{issue.message}</li>
            ))}
          </ul>
        ) : (
          <p>Checkout has nothing to offer for this course. Review its pricing, modules and sessions.</p>
        )}
      </div>
    </section>
  );
}
