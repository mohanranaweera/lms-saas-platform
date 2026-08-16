"use client";

import { useParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { isApiClientError } from "@/lib/api/error";
import { usePublicCourse } from "@/lib/api/public-courses";

const DETAIL_FIELDS: Array<{ key: "subject" | "stream" | "grade" | "academicYear"; label: string }> = [
  { key: "subject", label: "Subject" },
  { key: "stream", label: "Stream" },
  { key: "grade", label: "Grade" },
  { key: "academicYear", label: "Academic year" },
];

/**
 * Public course detail. A 404 here (`retry: false` on `usePublicCourse`) is
 * a deliberate, generic anti-enumeration response covering DRAFT/PRIVATE-in-
 * this-tenant, nonexistent, and cross-tenant-same-slug cases alike — this
 * page must never try to distinguish or hint at which of those applies, and
 * must never route that case through the scarier generic `ErrorState`
 * styling. Any other error status still falls back to the normal
 * `ErrorState` pattern.
 */
export default function PublicCourseDetailPage() {
  const params = useParams<{ slug: string }>();
  const slug = params.slug;
  const query = usePublicCourse(slug);

  const is404 =
    query.status === "error" && isApiClientError(query.error) && query.error.status === 404;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-10 sm:px-6">
      {query.status === "pending" ? <LoadingState label="Loading course…" /> : null}

      {query.status === "error" && is404 ? (
        <EmptyState
          title="Course not found"
          description="Course not found — it may be unpublished or the link may be incorrect."
        />
      ) : null}

      {query.status === "error" && !is404 ? (
        <ErrorState
          message={
            isApiClientError(query.error)
              ? query.error.message
              : "Something went wrong. Please try again."
          }
          code={isApiClientError(query.error) ? query.error.code : undefined}
          onRetry={() => query.refetch()}
        />
      ) : null}

      {query.status === "success" ? (
        <article className="flex flex-col gap-6">
          <div>
            <span className="inline-flex w-fit rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
              {query.data.category}
            </span>
            <h1 className="mt-2 text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
              {query.data.name}
            </h1>
          </div>

          {DETAIL_FIELDS.some((field) => query.data[field.key]) ? (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border p-4 text-sm sm:grid-cols-4">
              {DETAIL_FIELDS.filter((field) => query.data[field.key]).map((field) => (
                <div key={field.key}>
                  <dt className="text-xs font-medium text-muted-foreground">{field.label}</dt>
                  <dd className="text-foreground">{query.data[field.key]}</dd>
                </div>
              ))}
            </dl>
          ) : null}

          {query.data.description ? (
            <p className="whitespace-pre-line text-sm text-foreground">{query.data.description}</p>
          ) : null}

          <dl className="grid grid-cols-1 gap-4 rounded-lg border border-border p-4 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-xs font-medium text-muted-foreground">Price</dt>
              <dd className="text-base font-semibold text-foreground">
                {query.data.price.toFixed(2)}
              </dd>
            </div>
            <div>
              <dt className="text-xs font-medium text-muted-foreground">Access duration</dt>
              <dd className="text-foreground">
                {query.data.accessDurationDays
                  ? `${query.data.accessDurationDays} days`
                  : "Lifetime access"}
              </dd>
            </div>
          </dl>

          {query.data.enrollmentRule ? (
            <div>
              <h2 className="text-sm font-medium text-foreground">Enrollment rule</h2>
              <p className="mt-1 whitespace-pre-line text-sm text-muted-foreground">
                {query.data.enrollmentRule}
              </p>
            </div>
          ) : null}
        </article>
      ) : null}
    </div>
  );
}
