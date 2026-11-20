"use client";

import { Suspense, useState, type ReactNode } from "react";
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
import { shortId } from "@/lib/format";
import { useT } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { useLocaleFormat } from "@/i18n/use-locale-format";
import {
  useMyEnrollments,
  useMyReactivationRequests,
  useSubmitReactivationRequest,
  type ReactivationRequestResponse,
} from "@/lib/api/enrollments";
import {
  LegacyEnrollmentCard,
  useLegacyReactivationState,
} from "@/components/courses/student/legacy-reactivation";

/**
 * Confirm-and-submit panel for `enrollmentId` present in the URL
 * (`?enrollmentId=...`, linked from the Enrolled view's "Reactivate access"
 * action and from this page's own expired-courses list). `POST /api/v1/enrollments/{enrollmentId}/reactivation-requests`
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
  const t = useT();
  const enrollmentLabel = shortId(enrollmentId, t("student.common.enrollmentIdLabel"));

  async function handleSubmit() {
    setError(null);
    try {
      await mutation.mutateAsync();
      setSubmitted(true);
    } catch (submitError) {
      setError(localizeApiError(t, submitError));
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
            {t("student.reactivation.submittedFor", { enrollment: enrollmentLabel })}
          </h2>
        </div>
        <p className="text-sm text-muted-foreground">
          {t("student.reactivation.submittedDescription")}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h2 className="text-base font-medium text-foreground">
          {t("student.reactivation.requestFor", { enrollment: enrollmentLabel })}
        </h2>
        <p className="text-sm text-muted-foreground">
          {t("student.reactivation.requestDescription")}
        </p>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <LiveRegion message={mutation.isPending ? t("student.reactivation.submittingLive") : ""} />

      <Button
        type="button"
        onClick={handleSubmit}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        className="self-start"
      >
        {mutation.isPending ? t("student.reactivation.submitting") : t("student.reactivation.submit")}
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
  const t = useT();
  const { formatDateTime } = useLocaleFormat();
  const enrollmentLabel = (id: string) => shortId(id, t("student.common.enrollmentIdLabel"));
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
        aria-label={t("student.reactivation.checkoutFor", { enrollment: enrollmentLabel(row.enrollmentId) })}
        className="text-sm font-medium text-foreground hover:underline"
      >
        {t("student.reactivation.checkout")}
      </Link>
    );
  }

  const columns: DataTableColumn<ReactivationRequestResponse>[] = [
    {
      key: "enrollment",
      header: t("student.reactivation.col.enrollment"),
      cell: (row) => enrollmentLabel(row.enrollmentId),
      hideOnCard: true,
    },
    {
      key: "status",
      header: t("student.reactivation.col.status"),
      cell: (row) => <ReactivationStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "submitted",
      header: t("student.reactivation.col.submitted"),
      cell: (row) => formatDateTime(row.createdAt),
    },
    {
      key: "reviewed",
      header: t("student.reactivation.col.reviewed"),
      cell: (row) => (row.reviewedAt ? formatDateTime(row.reviewedAt) : t("student.reactivation.notReviewed")),
    },
    {
      key: "action",
      header: t("student.reactivation.col.action"),
      cell: (row) => renderAction(row) ?? "—",
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <h2 className="text-base font-medium text-foreground">{t("student.reactivation.historyHeading")}</h2>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("student.reactivation.historyLoading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: t("student.reactivation.historyEmpty.title"),
          description: t("student.reactivation.historyEmpty.description"),
        }}
      >
        {(data) => (
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            caption={t("student.reactivation.historyCaption")}
            cardHeading={(row) => enrollmentLabel(row.enrollmentId)}
            cardHeadingAdornment={(row) => <ReactivationStatusBadge status={row.status} />}
            cardFooter={renderAction}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}

/**
 * Entry list shown when the page is opened without `?enrollmentId=` (sidebar
 * "Reactivation", dashboard "Reactivate access"): the student's EXPIRED legacy
 * (paid, ADR-013) enrollments, each linking to the submit panel. Free
 * participation (ADR-028) never expires, so it never appears here. The CTA
 * per row follows the same backend-derived rules as the catalog's Enrolled
 * view (pending / approved-unfulfilled / requestable).
 */
function ExpiredEnrollmentsToRenew() {
  const state = useLegacyReactivationState();
  const { enrollmentsQuery, expiredRows } = state;
  const t = useT();

  let body: ReactNode;
  if (enrollmentsQuery.isPending) {
    body = (
      <p className="text-sm text-muted-foreground" aria-live="polite">
        {t("student.reactivation.expiredLoading")}
      </p>
    );
  } else if (enrollmentsQuery.isError) {
    body = (
      <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        {t("student.reactivation.expiredError")}
        <button
          type="button"
          onClick={() => enrollmentsQuery.refetch()}
          className="font-medium text-foreground underline hover:no-underline"
        >
          {t("common.actions.retry")}
        </button>
      </p>
    );
  } else if (expiredRows.length === 0) {
    body = (
      <p className="text-sm text-muted-foreground">
        {t("student.reactivation.noneExpired")}
      </p>
    );
  } else {
    body = (
      <ul aria-label={t("student.reactivation.expiredHeading")} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {expiredRows.map((row) => (
          <LegacyEnrollmentCard
            key={row.enrollmentId}
            row={row}
            state={state}
            reactivateText={t("student.reactivation.requestButton")}
          />
        ))}
      </ul>
    );
  }

  return (
    <section aria-labelledby="expired-enrollments-heading" className="flex flex-col gap-3" data-testid="reactivation-expired-list">
      <h2 id="expired-enrollments-heading" className="text-base font-medium text-foreground">
        {t("student.reactivation.expiredHeading")}
      </h2>
      {body}
    </section>
  );
}

function ReactivationPageContent() {
  const searchParams = useSearchParams();
  const enrollmentId = searchParams.get("enrollmentId");
  const t = useT();

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-8">
      <div>
        <h1 className="text-xl font-semibold break-words text-foreground">{t("student.reactivation.title")}</h1>
        <p className="text-sm text-muted-foreground">
          {t("student.reactivation.subtitle")}
        </p>
      </div>

      {enrollmentId ? (
        <SubmitReactivationPanel enrollmentId={enrollmentId} />
      ) : (
        <ExpiredEnrollmentsToRenew />
      )}

      <ReactivationHistory />
    </div>
  );
}

export default function StudentReactivationPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ReactivationPageContent />
    </Suspense>
  );
}
