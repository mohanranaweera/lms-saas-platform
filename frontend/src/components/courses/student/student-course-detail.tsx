"use client";

import { useState, type KeyboardEvent, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ClipboardList, Info, Loader2, MonitorPlay, Truck, UserRound, Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import {
  useCourseContent,
  useSelfEnroll,
  useStudentCourse,
  type LessonNode,
  type PeriodNode,
  type StudentCourseDetail,
} from "@/lib/api/klass-student";
import { usePaymentComposer } from "@/components/payments/composer/payment-composer-provider";
import { presetFromPurchase } from "@/components/payments/composer/composer-model";
import { CourseContentTree } from "./course-content-tree";
import { CourseCover, PriceBadge } from "./course-visuals";
import { CourseExamsTab } from "./course-exams-tab";
import { CourseLiveClassesTab } from "./course-live-classes-tab";
import { EnrollConfirmDialog, enrollErrorMessage } from "./enroll-confirm-dialog";
import { useLessonLauncher } from "./lesson-launcher";
import { StudentToast, useStudentToast } from "./student-toast";

type DetailTab = "content" | "exams" | "live" | "tutes" | "about";

const TABS: { value: DetailTab; label: string; icon: ReactNode }[] = [
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

  const teacherNames =
    course.teachers && course.teachers.length > 0
      ? [...course.teachers].sort((a, b) => Number(b.primary) - Number(a.primary)).map((t) => t.name).join(", ")
      : null;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6" data-testid="student-course-detail">
      <nav aria-label="Breadcrumb">
        <Link href="/student/courses" className="text-sm text-muted-foreground hover:underline">
          ← My courses
        </Link>
      </nav>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="flex flex-col gap-4 p-4 sm:p-6 md:flex-row md:items-center md:gap-8" data-testid="course-header-card">
          <CourseCover src={course.coverImageUrl} title={course.name} className="rounded-lg md:w-2/5 md:max-w-sm" />
          <div className="flex min-w-0 flex-col gap-2">
            <PriceBadge priceDisplay={course.priceDisplay} pricingModel={course.pricingModel} />
            <h1 className="text-xl font-bold break-words text-foreground sm:text-2xl" data-testid="course-detail-title">
              {course.name}
            </h1>
            {teacherNames ? (
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <UserRound className="size-4" aria-hidden="true" />
                <span className="sr-only">Teacher:</span>
                {teacherNames}
              </p>
            ) : null}
            {headerCta ? <div className="pt-2">{headerCta}</div> : null}
          </div>
        </div>
        <DetailTabs tab={tab} onChange={setTab} />
      </div>

      <LiveRegion message={launcher.notice} />

      <div role="tabpanel" id="course-detail-tabpanel" aria-labelledby={`course-detail-tab-${tab}`}>
        {tab === "content" ? (
          <ContentTab
            courseId={course.id}
            expanded={expanded}
            onToggle={toggleFolder}
            launchingId={launcher.launchingId}
            onLaunch={launcher.launch}
            onPayPeriod={payPeriod}
            onPayLesson={payLesson}
            notEnrolled={notEnrolled}
          />
        ) : tab === "exams" ? (
          <CourseExamsTab courseId={course.id} timeZone={timeZone} />
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
          <div className="rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6" data-testid="course-tutes-empty">
            <EmptyState
              icon={<Truck className="size-8" />}
              title="No tutes for this course yet"
              description="When your institute sends printed tutes, you can follow them here."
            />
          </div>
        ) : (
          <AboutTab course={course} />
        )}
      </div>

      {launcher.element}
      <StudentToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}

function DetailTabs({ tab, onChange }: { tab: DetailTab; onChange: (tab: DetailTab) => void }) {
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % TABS.length;
    if (event.key === "ArrowLeft") next = (index - 1 + TABS.length) % TABS.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = TABS.length - 1;
    onChange(TABS[next].value);
    document.getElementById(`course-detail-tab-${TABS[next].value}`)?.focus();
  };

  return (
    <div role="tablist" aria-label="Course sections" className="flex flex-nowrap overflow-x-auto border-t border-border px-2">
      {TABS.map((item, index) => {
        const selected = item.value === tab;
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            id={`course-detail-tab-${item.value}`}
            aria-selected={selected}
            aria-controls="course-detail-tabpanel"
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(item.value)}
            onKeyDown={(event) => onKeyDown(event, index)}
            data-testid={`course-tab-${item.value}`}
            className={cn(
              "inline-flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm font-medium outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
              selected
                ? "border-blue-600 text-blue-700 dark:text-blue-300"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {item.icon}
            {item.label}
          </button>
        );
      })}
    </div>
  );
}

function ContentTab({
  courseId,
  expanded,
  onToggle,
  launchingId,
  onLaunch,
  onPayPeriod,
  onPayLesson,
  notEnrolled,
}: {
  courseId: string;
  expanded: ReadonlySet<string>;
  onToggle: (id: string) => void;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayPeriod: ((period: PeriodNode) => void) | null;
  onPayLesson: ((lesson: LessonNode) => void) | null;
  notEnrolled: ReactNode;
}) {
  const query = useCourseContent(courseId);
  if (query.status === "pending") return <LoadingState label="Loading course content…" />;
  if (query.status === "error") {
    if (isApiClientError(query.error) && (query.error.status === 404 || query.error.status === 403)) {
      return <>{notEnrolled}</>;
    }
    return (
      <ErrorState
        message="We couldn't load this course's content."
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }
  return (
    <CourseContentTree
      content={query.data}
      expanded={expanded}
      onToggle={onToggle}
      launchingId={launchingId}
      onLaunch={onLaunch}
      onPayPeriod={onPayPeriod}
      onPayLesson={onPayLesson}
    />
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
  const canEnroll = course.status === "PUBLIC" && course.registrationEnabled !== false && course.archivedAt === null;

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

function safeInviteUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" ? parsed.toString() : null;
  } catch {
    return null;
  }
}

function AboutTab({ course }: { course: StudentCourseDetail }) {
  const whatsapp = safeInviteUrl(course.whatsappInviteUrl);
  const telegram = safeInviteUrl(course.telegramInviteUrl);
  return (
    <section
      aria-labelledby="about-course-heading"
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6"
      data-testid="course-about-panel"
    >
      <h2 id="about-course-heading" className="text-base font-semibold text-foreground">
        About this course
      </h2>
      {course.description ? (
        // Plain text only - never rendered as HTML.
        <p className="text-sm whitespace-pre-wrap text-foreground" data-testid="course-about-description">
          {course.description}
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">Your teacher hasn&apos;t added a description yet.</p>
      )}
      {whatsapp || telegram ? (
        <div className="flex flex-col gap-2 border-t border-border pt-4">
          <h3 className="text-sm font-semibold text-foreground">Join the class groups</h3>
          <div className="flex flex-wrap gap-2">
            {whatsapp ? (
              <a
                href={whatsapp}
                target="_blank"
                rel="noopener noreferrer"
                data-testid="course-about-whatsapp"
                className="inline-flex items-center rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
              >
                Join WhatsApp group
              </a>
            ) : null}
            {telegram ? (
              <a
                href={telegram}
                target="_blank"
                rel="noopener noreferrer"
                data-testid="course-about-telegram"
                className="inline-flex items-center rounded-lg bg-sky-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-sky-700"
              >
                Join Telegram group
              </a>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  );
}
