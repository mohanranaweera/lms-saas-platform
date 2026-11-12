"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ClipboardList, Info, Loader2, MonitorPlay, Truck, Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { isApiClientError } from "@/lib/api/error";
import {
  useSelfEnroll,
  useStudentCourse,
  type LessonNode,
  type PeriodNode,
  type StudentCourseDetail,
} from "@/lib/api/klass-student";
import { usePaymentComposer } from "@/components/payments/composer/payment-composer-provider";
import { presetFromPurchase } from "@/components/payments/composer/composer-model";
import { CourseAboutPanel, CourseContentTab, CourseDetailFrame, type CourseDetailTab } from "./course-detail-layout";
import { CourseExamsTab } from "./course-exams-tab";
import { CourseLiveClassesTab } from "./course-live-classes-tab";
import { CourseTutesTab } from "./course-tutes-tab";
import { useSelfEnrollmentEnabled } from "@/lib/api/public-home";
import { EnrollConfirmDialog, enrollErrorMessage } from "./enroll-confirm-dialog";
import { useLessonLauncher } from "./lesson-launcher";
import { StudentToast, useStudentToast } from "./student-toast";

type DetailTab = "content" | "exams" | "live" | "tutes" | "about";

const TABS: CourseDetailTab<DetailTab>[] = [
  { value: "content", label: "Course content", icon: <MonitorPlay className="size-4" aria-hidden="true" /> },
  { value: "exams", label: "Exams", icon: <ClipboardList className="size-4" aria-hidden="true" /> },
  { value: "live", label: "Live Classes", icon: <Video className="size-4" aria-hidden="true" /> },
  { value: "tutes", label: "Tute Delivery", icon: <Truck className="size-4" aria-hidden="true" /> },
  { value: "about", label: "About this course", icon: <Info className="size-4" aria-hidden="true" /> },
];

function tabFromParam(value: string | null): DetailTab {
  return TABS.some((tab) => tab.value === value) ? (value as DetailTab) : "content";
}

/**
 * Student course detail (CM-S02..S06, CM-R01..R03): a persistent header card
 * and five URL-synced tabs. Everything access-related is the server's
 * answer: the content tree and live classes come from the lesson evaluator,
 * and opening a lesson always goes through the server launch. Enrollment is
 * the free, server-confirmed action (ADR-028).
 */
export function StudentCourseDetailView({ courseId, headerCta }: { courseId: string; headerCta?: ReactNode }) {
  const courseQuery = useStudentCourse(courseId);

  if (courseQuery.status === "pending") return <LoadingState label="Loading course…" />;
  if (courseQuery.status === "error") {
    const status = isApiClientError(courseQuery.error) ? courseQuery.error.status : undefined;
    if (status === 403 || status === 404) {
      return (
        <div data-testid="course-detail-unavailable">
          <EmptyState
            title="This course isn't available"
            description="It may have been removed, or it isn't open to you. Go back to your courses to find another one."
          />
          <div className="mt-4 text-center">
            <Link href="/student/courses" className="text-sm font-medium text-blue-700 hover:underline">
              Back to my courses
            </Link>
          </div>
        </div>
      );
    }
    return (
      <ErrorState
        message="We couldn't load this course."
        code={isApiClientError(courseQuery.error) ? courseQuery.error.code : undefined}
        onRetry={() => courseQuery.refetch()}
      />
    );
  }

  return <CourseDetailLoaded course={courseQuery.data} headerCta={headerCta} />;
}

function CourseDetailLoaded({ course, headerCta }: { course: StudentCourseDetail; headerCta?: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tab = tabFromParam(searchParams.get("tab"));
  const composer = usePaymentComposer();
  const toast = useStudentToast();
  const timeZone = course.timezone ?? null;

  // Folder expansion lives here so it survives tab switches and every dialog.
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const toggleFolder = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const setTab = (next: DetailTab) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === "content") params.delete("tab");
    else params.set("tab", next);
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const titleFor = (purchaseCourseId: string) => (purchaseCourseId === course.id ? course.name : undefined);

  const payLesson =
    composer === null
      ? null
      : (lesson: LessonNode) => {
          const purchase = lesson.access.requiredPurchase;
          if (!purchase) return;
          composer.openComposer(
            presetFromPurchase(purchase, {
              courseTitle: titleFor(purchase.courseId),
              itemLabel: purchase.scope === "LESSON" ? lesson.title : undefined,
            })
          );
        };

  const payPeriod =
    composer === null
      ? null
      : (period: PeriodNode) => {
          const purchase = period.payment.purchase;
          if (!purchase) return;
          composer.openComposer(presetFromPurchase(purchase, { courseTitle: titleFor(purchase.courseId) }));
        };

  const launcher = useLessonLauncher({
    pageCourseId: course.id,
    timeZone,
    onPay: (lesson) => payLesson?.(lesson),
  });

  const notEnrolled = <NotEnrolledPanel course={course} onEnrolled={(text) => toast.show({ kind: "success", text })} />;

  return (
    <CourseDetailFrame
      course={course}
      backHref="/student/courses"
      backLabel="My courses"
      headerCta={headerCta}
      tabs={TABS}
      tab={tab}
      onTabChange={setTab}
      testId="student-course-detail"
    >
      <LiveRegion message={launcher.notice} />

      <div role="tabpanel" id="course-detail-tabpanel" aria-labelledby={`course-detail-tab-${tab}`}>
        {tab === "content" ? (
          <CourseContentTab
            courseId={course.id}
            expanded={expanded}
            onToggle={toggleFolder}
            launchingId={launcher.launchingId}
            onLaunch={launcher.launch}
            onPayPeriod={payPeriod}
            onPayLesson={payLesson}
            denied={notEnrolled}
          />
        ) : tab === "exams" ? (
          <CourseExamsTab courseId={course.id} courseName={course.name} />
        ) : tab === "live" ? (
          <CourseLiveClassesTab
            courseId={course.id}
            timeZone={timeZone}
            launchingId={launcher.launchingId}
            onLaunch={launcher.launch}
            onPayLesson={payLesson}
            notEnrolled={notEnrolled}
          />
        ) : tab === "tutes" ? (
          <CourseTutesTab courseId={course.id} timeZone={timeZone} />
        ) : (
          <CourseAboutPanel course={course} />
        )}
      </div>

      {launcher.element}
      <StudentToast message={toast.message} onDismiss={toast.dismiss} />
    </CourseDetailFrame>
  );
}

/**
 * Shown when the server says the student isn't a participant (content /
 * live-classes 404). Offers the same free, confirmed enrollment as the
 * catalog; nothing is shown as enrolled until the server confirms.
 */
function NotEnrolledPanel({ course, onEnrolled }: { course: StudentCourseDetail; onEnrolled: (text: string) => void }) {
  const enroll = useSelfEnroll();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const selfEnrollmentEnabled = useSelfEnrollmentEnabled();
  const open = course.status === "PUBLIC" && course.registrationEnabled !== false && course.archivedAt === null;
  // SET-CFG-01: when the institute enrolls students itself there is no Enroll button (the backend also refuses).
  const canEnroll = open && selfEnrollmentEnabled;

  const run = async () => {
    setError(null);
    try {
      await enroll.mutateAsync(course.id);
      onEnrolled(`You're enrolled in ${course.name}.`);
    } catch (err) {
      setError(enrollErrorMessage(err));
    }
  };

  return (
    <div
      className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border bg-card px-6 py-12 text-center"
      data-testid="course-not-enrolled"
    >
      <p className="text-sm font-medium text-foreground">You&apos;re not enrolled in this course</p>
      <p className="max-w-md text-sm text-muted-foreground">
        {canEnroll
          ? "Enroll for free to see the course content and live classes. Paid lessons ask for payment when you open them."
          : open
            ? "Your institute enrolls students in courses. Contact your institute to join this course."
            : "This course isn't open for enrollment right now."}
      </p>
      {canEnroll ? (
        <Button
          type="button"
          onClick={() => setConfirmOpen(true)}
          disabled={enroll.isPending}
          aria-busy={enroll.isPending}
          data-testid="course-detail-enroll"
          className="min-w-24 bg-blue-600 text-white hover:bg-blue-700"
        >
          {enroll.isPending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : "Enroll"}
          {enroll.isPending ? <span className="sr-only">Enrolling…</span> : null}
        </Button>
      ) : null}
      {error ? (
        <div role="alert" className="flex flex-col items-center gap-1 text-sm text-destructive" data-testid="course-detail-enroll-error">
          <span>{error}</span>
          <button type="button" className="font-medium underline" onClick={run} disabled={enroll.isPending}>
            Try again
          </button>
        </div>
      ) : null}
      <EnrollConfirmDialog
        courseTitle={course.name}
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        onConfirm={() => {
          setConfirmOpen(false);
          void run();
        }}
      />
    </div>
  );
}
