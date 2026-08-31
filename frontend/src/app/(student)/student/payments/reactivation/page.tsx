"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { ReactivationStatusBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import { isApiClientError } from "@/lib/api/error";
import {
  useMyEnrollments,
  useMyReactivationRequests,
  useSubmitReactivationRequest,
  type ReactivationRequestResponse,
} from "@/lib/api/enrollments";

/**
 * Confirm-and-submit panel for `enrollmentId` present in the URL
 * (`?enrollmentId=...`, linked from "My Courses"'s per-row Reactivate
 * action). `POST /api/v1/enrollments/{enrollmentId}/reactivation-requests`
 * takes no request body — there is nothing here for React Hook Form/Zod to
 * validate, so this is a plain confirm button, not a form.
 *
 * On success this never activates or implies access has been restored — it
 * only confirms the request was recorded; activation is a separate,
 * backend-confirmed event that follows Tenant Admin approval and a brand-new
 * order/payment, both outside this screen.
 */
function SubmitReactivationPanel({ enrollmentId }: { enrollmentId: string }) {
  const mutation = useSubmitReactivationRequest(enrollmentId);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  async function handleSubmit() {
    setError(null);
    try {
      await mutation.mutateAsync();
      setSubmitted(true);
    } catch (submitError) {
      setError(
        isApiClientError(submitError)
          ? submitError.message
          : "Something went wrong. Please try again."
      );
    }
  }

  if (submitted) {
    return (
      <div
        role="status"
        aria-live="polite"
        className="flex flex-col gap-2 rounded-lg border border-border p-4"
      >
        <div className="flex items-center gap-2">
          <CheckCircle2 className="size-5 text-foreground" aria-hidden="true" />
          <h2 className="text-base font-medium text-foreground">
            Reactivation request submitted for {shortId(enrollmentId, "Enrollment")}
          </h2>
        </div>
        <p className="text-sm text-muted-foreground">
          A Tenant Admin will review your request. Track its status in your request history
          below — you&apos;ll be able to place a new order once it&apos;s approved.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h2 className="text-base font-medium text-foreground">
          Request reactivation for {shortId(enrollmentId, "Enrollment")}
        </h2>
        <p className="text-sm text-muted-foreground">
          Your access to this course has expired. Submitting a request notifies your institute&apos;s
          admin — if approved, you&apos;ll be able to place a new order to restore access.
        </p>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <LiveRegion message={mutation.isPending ? "Submitting reactivation request…" : ""} />

      <Button
        type="button"
        onClick={handleSubmit}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        className="self-start"
      >
        {mutation.isPending ? "Submitting…" : "Submit reactivation request"}
      </Button>
    </div>
  );
}

/**
 * Always-rendered request history (`GET /api/v1/reactivation-requests/my`).
 * `ReactivationRequestResponse` carries no `courseId` — only `enrollmentId`
 * — so each row renders an enrollment id fragment (`shortId(..., "Enrollment")`),
 * not a course id fragment; there is no way to resolve which course a given
 * request belongs to from this endpoint's response shape alone.
 *
 * The one exception is the "Proceed to checkout" action on an `APPROVED`,
 * not-yet-fulfilled (`newOrderId == null`) row: `GET /api/v1/enrollments/my`
 * is fetched here too, purely to resolve `enrollmentId -> courseId` for that
 * one action (an approved-unfulfilled request's `enrollmentId` always
 * matches a still-CURRENT — i.e. not yet superseded — enrollment, per plan
 * §12, so it is guaranteed to appear in that list). If the lookup hasn't
 * resolved yet (or the enrollment isn't found for some reason), the action
 * is simply omitted for that row rather than rendered with a broken link.
 */
function ReactivationHistory() {
  const query = useMyReactivationRequests({ page: 0, size: 20 });
  const enrollmentsQuery = useMyEnrollments();
  const courseIdByEnrollmentId = new Map(
    (enrollmentsQuery.data ?? []).map((enrollment) => [enrollment.enrollmentId, enrollment.courseId])
  );

  function renderAction(row: ReactivationRequestResponse) {
    if (row.status !== "APPROVED" || row.newOrderId !== null) return null;
    const courseId = courseIdByEnrollmentId.get(row.enrollmentId);
    if (!courseId) return null;
    return (
      <Link
        href={`/student/checkout/${courseId}`}
        aria-label={`Proceed to checkout for ${shortId(row.enrollmentId, "Enrollment")}`}
        className="text-sm font-medium text-foreground hover:underline"
      >
        Proceed to checkout
      </Link>
    );
  }

  const columns: DataTableColumn<ReactivationRequestResponse>[] = [
    {
      key: "enrollment",
      header: "Enrollment",
      cell: (row) => shortId(row.enrollmentId, "Enrollment"),
      hideOnCard: true,
    },
    {
      key: "status",
      header: "Status",
      cell: (row) => <ReactivationStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "submitted",
      header: "Submitted",
      cell: (row) => formatDateTime(row.createdAt),
    },
    {
      key: "reviewed",
      header: "Reviewed",
      cell: (row) => (row.reviewedAt ? formatDateTime(row.reviewedAt) : "Not yet reviewed"),
    },
    {
      key: "action",
      header: "Action",
      cell: (row) => renderAction(row) ?? "—",
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <h2 className="text-base font-medium text-foreground">Your reactivation requests</h2>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your reactivation requests…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "You haven't requested reactivation for any course yet",
          description:
            "Once your access to a course expires, you can request reactivation from My Courses.",
        }}
      >
        {(data) => (
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            caption="Your reactivation request history"
            cardHeading={(row) => shortId(row.enrollmentId, "Enrollment")}
            cardHeadingAdornment={(row) => <ReactivationStatusBadge status={row.status} />}
            cardFooter={renderAction}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}

function ReactivationPageContent() {
  const searchParams = useSearchParams();
  const enrollmentId = searchParams.get("enrollmentId");

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-8">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Reactivation</h1>
        <p className="text-sm text-muted-foreground">
          Request reactivation for a course whose access has expired, and track the status of
          past requests.
        </p>
      </div>

      {enrollmentId ? <SubmitReactivationPanel enrollmentId={enrollmentId} /> : null}

      <ReactivationHistory />
    </div>
  );
}

export default function StudentReactivationPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading…" />}>
      <ReactivationPageContent />
    </Suspense>
  );
}
