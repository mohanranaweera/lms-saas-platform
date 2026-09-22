"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { Button } from "@/components/ui/button";
import { isApiClientError } from "@/lib/api/error";
import { usePublicCourse } from "@/lib/api/public-courses";
import { useAuth } from "@/lib/auth/auth-context";
import {
  CoursePriceText,
  CoursePricingNotice,
  isCourseCheckoutAvailable,
} from "@/components/courses/course-price-display";

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
  const { session, ensureAccessToken } = useAuth();

  const is404 =
    query.status === "error" && isApiClientError(query.error) && query.error.status === 404;

  /**
   * The access token is memory-only per tab (see auth-context.tsx), so a
   * student who logged in earlier and then opened this page in a fresh tab
   * (or via a direct/bookmarked link) would otherwise show `session === null`
   * here even though their refresh-token cookie is still valid. This page
   * never makes an authenticated request on its own (the public course
   * lookup doesn't need one) to trigger that self-heal, so it's done
   * explicitly on mount, best-effort - a failure just means "not signed in",
   * which is already the correct fallback (render the sign-in CTA below).
   */
  const [checkingAuth, setCheckingAuth] = useState(true);
  useEffect(() => {
    let cancelled = false;
    ensureAccessToken("tenant")
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setCheckingAuth(false);
      });
    return () => {
      cancelled = true;
    };
    // Intentionally run once per mount only - re-running on every `session`/
    // `ensureAccessToken` identity change (both change on login/logout, since
    // `ensureAccessToken` closes over `session`) would re-trigger this
    // best-effort check pointlessly after it has already resolved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const isStudent = session?.kind === "tenant" && session.role === "STUDENT";

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
                <CoursePriceText course={query.data} />
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

          <CoursePricingNotice course={query.data} />

          {query.data.enrollmentRule ? (
            <div>
              <h2 className="text-sm font-medium text-foreground">Enrollment rule</h2>
              <p className="mt-1 whitespace-pre-line text-sm text-muted-foreground">
                {query.data.enrollmentRule}
              </p>
            </div>
          ) : null}

          <div>
            {!isCourseCheckoutAvailable(query.data) ? (
              <p className="text-sm text-muted-foreground">
                {query.data.requiresManualQuote
                  ? "Enrollment for this course is arranged manually by our staff — contact the institute to enroll."
                  : "Enrollment isn't open yet — pricing for this course hasn't been configured."}
              </p>
            ) : checkingAuth ? (
              <Button type="button" disabled aria-busy="true">
                Loading…
              </Button>
            ) : isStudent ? (
              <Button render={<Link href={`/student/checkout/${query.data.id}`} />}>
                Enroll now
              </Button>
            ) : (
              <Button render={<Link href="/login" />}>Sign in to enroll</Button>
            )}
          </div>
        </article>
      ) : null}
    </div>
  );
}
