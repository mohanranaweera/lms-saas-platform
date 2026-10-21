"use client";

import { Suspense, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertCircle, ExternalLink, IdCard, Mail, Phone } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AddPaymentDialog } from "@/components/payments/staff/add-payment-dialog";
import { FreeCardsPanel } from "@/components/payments/staff/free-cards-panel";
import { IssueFreeCardDialog } from "@/components/payments/staff/issue-free-card-dialog";
import { PaidResourcesPanel } from "@/components/payments/staff/paid-resources-panel";
import { PaymentHistoryPanel } from "@/components/payments/staff/payment-history-panel";
import { NATIVE_SELECT_CLASS } from "@/components/payments/staff/staff-dialog";
import { isApiClientError } from "@/lib/api/error";
import { useStaffPaymentContext, type PaymentContext } from "@/lib/api/staff-payments";
import { useStudents } from "@/lib/api/students";
import { useAuth } from "@/lib/auth/auth-context";
import {
  canIssueFreeCard,
  canProcessRefunds,
  canRecordManualPayment,
  canRevokeFreeCard,
  canViewFreeCards,
} from "@/lib/auth/permissions";

const BASE_PATH = "/tenant-admin/payments/students";

/**
 * Student Payments (ADR-030, Klass "Manual Payment And Free Class Grant").
 *
 * `?studentId=` (student_profile id) and `?courseId=` are selection inputs
 * only: the backend resolves both inside the caller's tenant (404 otherwise)
 * and enforces every permission. Role helpers here only decide which
 * controls render. Recording a payment / issuing a free card is shown as done
 * only after the server answers; nothing is unlocked from this page.
 */
export default function StudentPaymentsPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading…" />}>
      <StudentPaymentsContent />
    </Suspense>
  );
}

function StudentPaymentsContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const studentId = searchParams.get("studentId") || null;
  const courseId = searchParams.get("courseId") || null;
  const [announcement, setAnnouncement] = useState("");

  const select = (nextStudentId: string | null, nextCourseId: string | null) => {
    const params = new URLSearchParams();
    if (nextStudentId) params.set("studentId", nextStudentId);
    if (nextStudentId && nextCourseId) params.set("courseId", nextCourseId);
    const query = params.toString();
    router.replace(query ? `${BASE_PATH}?${query}` : BASE_PATH);
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Student Payments</h1>
          <p className="text-sm text-muted-foreground">
            Record money a student paid at the institute, see their payments for a course, and manage free cards.
          </p>
        </div>
        <Link href="/tenant-admin/payments/free-cards" className={buttonVariants({ variant: "outline" })}>
          All free cards
        </Link>
      </div>
      <LiveRegion message={announcement} />
      {/* Keyed by the student so every selection change starts from fresh panel/dialog state. */}
      <StudentPaymentsWorkspace
        key={studentId ?? "none"}
        studentId={studentId}
        courseId={courseId}
        onSelect={select}
        onAnnounce={setAnnouncement}
      />
    </div>
  );
}

function StudentPaymentsWorkspace({
  studentId,
  courseId,
  onSelect,
  onAnnounce,
}: {
  studentId: string | null;
  courseId: string | null;
  onSelect: (studentId: string | null, courseId: string | null) => void;
  onAnnounce: (message: string) => void;
}) {
  const context = useStaffPaymentContext(studentId);
  const notFound = isApiClientError(context.error) && context.error.status === 404;

  return (
    <>
      <SelectorCard studentId={studentId} courseId={courseId} context={context.data ?? null} onSelect={onSelect} />
      {!studentId ? (
        <EmptyState
          title="Select a student"
          description="Search for a student above to see their payments and free cards, or open this page from a student's Payments tab."
        />
      ) : notFound ? (
        <Alert variant="destructive" data-testid="student-not-found">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>Student not found / not in your institute. Select another student.</AlertDescription>
        </Alert>
      ) : (
        <QueryStateBoundary
          query={context}
          loadingLabel="Loading student…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        >
          {(data) => (
            <StudentWorkspace
              key={courseId ?? "no-course"}
              context={data}
              courseId={courseId}
              onAnnounce={onAnnounce}
            />
          )}
        </QueryStateBoundary>
      )}
    </>
  );
}

function SelectorCard({
  studentId,
  courseId,
  context,
  onSelect,
}: {
  studentId: string | null;
  courseId: string | null;
  context: PaymentContext | null;
  onSelect: (studentId: string | null, courseId: string | null) => void;
}) {
  const students = useStudents();
  const [search, setSearch] = useState("");
  const normalized = search.trim().toLowerCase();
  const options = useMemo(() => {
    const all = students.data ?? [];
    const matches = normalized
      ? all.filter(
          (student) => student.name.toLowerCase().includes(normalized) || student.email.toLowerCase().includes(normalized)
        )
      : all;
    return [...matches].sort((a, b) => a.name.localeCompare(b.name));
  }, [students.data, normalized]);
  const selectedListed = students.data?.some((student) => student.id === studentId) ?? false;
  const studentsError = students.isError
    ? isApiClientError(students.error) && students.error.status === 403
      ? "You don't have permission to list students."
      : "Couldn't load the student list."
    : null;

  return (
    <div className="grid grid-cols-1 gap-4 rounded-lg border border-border bg-card p-4 md:grid-cols-2">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="student-payments-search">Find student</Label>
        <Input
          id="student-payments-search"
          type="search"
          placeholder="Type a name or email…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          aria-describedby="student-payments-student-hint"
          data-testid="student-search"
        />
        <Label htmlFor="student-payments-student" className="mt-2">
          Student *
        </Label>
        <select
          id="student-payments-student"
          className={NATIVE_SELECT_CLASS}
          value={studentId ?? ""}
          disabled={students.isPending && !studentId}
          onChange={(event) => onSelect(event.target.value || null, null)}
          data-testid="student-select"
        >
          <option value="">{students.isPending ? "Loading students…" : "Select a student"}</option>
          {studentId && !selectedListed ? (
            <option value={studentId}>{context?.student.name ?? "Student from link"}</option>
          ) : null}
          {options.map((student) => (
            <option key={student.id} value={student.id}>
              {student.name} ({student.email})
            </option>
          ))}
        </select>
        <p id="student-payments-student-hint" className="text-xs text-muted-foreground">
          {studentsError ??
            (normalized && options.length === 0 ? `No students match “${search.trim()}”.` : `${options.length} students`)}
        </p>
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="student-payments-course">Course *</Label>
        <select
          id="student-payments-course"
          className={NATIVE_SELECT_CLASS}
          value={courseId ?? ""}
          disabled={!context}
          onChange={(event) => onSelect(studentId, event.target.value || null)}
          aria-describedby="student-payments-course-hint"
          data-testid="course-select"
        >
          <option value="">{context ? "Select a course" : "Select a student first"}</option>
          {courseId && context && !context.courses.some((course) => course.courseId === courseId) ? (
            <option value={courseId}>Unknown course</option>
          ) : null}
          {(context?.courses ?? []).map((course) => (
            <option key={course.courseId} value={course.courseId}>
              {course.title}
            </option>
          ))}
        </select>
        <p id="student-payments-course-hint" className="text-xs text-muted-foreground">
          {context && context.courses.length === 0
            ? "This student isn't in any course yet."
            : "Courses this student is in."}
        </p>
      </div>
    </div>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase() || "?";
}

function StudentWorkspace({
  context,
  courseId,
  onAnnounce,
}: {
  context: PaymentContext;
  courseId: string | null;
  onAnnounce: (message: string) => void;
}) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const canRecord = canRecordManualPayment(role);
  const canRefund = canProcessRefunds(role);
  const canIssue = canIssueFreeCard(role);
  const canRevoke = canRevokeFreeCard(role);
  const canSeeFreeCards = canViewFreeCards(role);
  const [addOpen, setAddOpen] = useState(false);
  const [issueOpen, setIssueOpen] = useState(false);

  const { student } = context;
  const course = courseId ? context.courses.find((entry) => entry.courseId === courseId) ?? null : null;

  return (
    <div className="flex flex-col gap-6">
      <section
        className="flex flex-col gap-3 rounded-lg border border-border bg-muted/40 p-4 sm:flex-row sm:items-center sm:justify-between"
        aria-label="Student"
        data-testid="student-card"
      >
        <div className="flex items-center gap-3">
          <span
            className="inline-flex size-12 shrink-0 items-center justify-center rounded-full bg-blue-100 text-base font-semibold text-blue-800 dark:bg-blue-950 dark:text-blue-200"
            aria-hidden="true"
          >
            {initials(student.name)}
          </span>
          <div className="min-w-0">
            <p className="flex items-center gap-2 font-semibold text-foreground" data-testid="student-name">
              {student.name}
              <Link
                href={`/tenant-admin/students/${encodeURIComponent(student.studentId)}`}
                className="text-muted-foreground hover:text-foreground"
                aria-label={`Open ${student.name}'s profile`}
              >
                <ExternalLink className="size-4" aria-hidden="true" />
              </Link>
            </p>
            {student.email ? (
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Mail className="size-3.5" aria-hidden="true" />
                <span className="sr-only">Email: </span>
                {student.email}
              </p>
            ) : null}
            {student.mobile ? (
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Phone className="size-3.5" aria-hidden="true" />
                <span className="sr-only">Mobile: </span>
                {student.mobile}
              </p>
            ) : null}
          </div>
        </div>
        {canIssue ? (
          <Button type="button" variant="secondary" onClick={() => setIssueOpen(true)} data-testid="issue-free-card-open">
            <IdCard aria-hidden="true" />
            Issue a Free Card
          </Button>
        ) : null}
      </section>

      {!courseId ? (
        <EmptyState
          title="Select a course"
          description={
            context.courses.length === 0
              ? `${student.name} isn't in any course yet, so there are no payments to show.`
              : `Choose one of ${student.name}'s courses above to see its payments.`
          }
        />
      ) : !course ? (
        <Alert variant="destructive" data-testid="course-not-found">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>This course isn&apos;t one of {student.name}&apos;s courses. Select another course.</AlertDescription>
        </Alert>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <PaymentHistoryPanel
            studentId={student.studentId}
            studentName={student.name}
            courseId={course.courseId}
            courseTitle={course.title}
            canRecord={canRecord}
            canRefund={canRefund}
            onAddPayment={() => setAddOpen(true)}
          />
          <div className="flex flex-col gap-6">
            {canSeeFreeCards ? (
              <FreeCardsPanel
                studentId={student.studentId}
                courseId={course.courseId}
                courseTitle={course.title}
                canRevoke={canRevoke}
                onAnnounce={onAnnounce}
              />
            ) : null}
            <PaidResourcesPanel studentId={student.studentId} courseId={course.courseId} courseTitle={course.title} />
          </div>
        </div>
      )}

      {canRecord ? (
        <AddPaymentDialog open={addOpen} onOpenChange={setAddOpen} context={context} defaultCourseId={courseId} />
      ) : null}
      {canIssue ? (
        <IssueFreeCardDialog open={issueOpen} onOpenChange={setIssueOpen} context={context} defaultCourseId={courseId} />
      ) : null}
    </div>
  );
}
