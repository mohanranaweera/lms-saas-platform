"use client";

import type { KeyboardEvent, ReactNode } from "react";
import Link from "next/link";
import { UserRound } from "lucide-react";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import {
  useCourseContent,
  type LessonNode,
  type PeriodNode,
  type StudentCourseDetail,
} from "@/lib/api/klass-student";
import { CourseContentTree } from "./course-content-tree";
import { CourseCover, PriceBadge } from "./course-visuals";

/**
 * Shared course presentation (TP-02): the header card, URL-synced tab strip,
 * course-content tree and About panel used by BOTH the Student course detail
 * and the Teacher Portal course view. It holds no access, enrollment, payment
 * or progress logic - each caller supplies its own data/action model, and the
 * server decides what each caller may see.
 */

export interface CourseDetailTab<T extends string> {
  value: T;
  label: string;
  icon: ReactNode;
}

export function CourseDetailFrame<T extends string>({
  course,
  backHref,
  backLabel,
  headerCta,
  tabs,
  tab,
  onTabChange,
  children,
  testId,
}: {
  course: StudentCourseDetail;
  backHref: string;
  backLabel: string;
  headerCta?: ReactNode;
  tabs: CourseDetailTab<T>[];
  tab: T;
  onTabChange: (tab: T) => void;
  children: ReactNode;
  testId: string;
}) {
  const teacherNames =
    course.teachers && course.teachers.length > 0
      ? [...course.teachers].sort((a, b) => Number(b.primary) - Number(a.primary)).map((t) => t.name).join(", ")
      : null;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6" data-testid={testId}>
      <nav aria-label="Breadcrumb">
        <Link href={backHref} className="text-sm text-muted-foreground hover:underline">
          ← {backLabel}
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
        <CourseDetailTabs tabs={tabs} tab={tab} onChange={onTabChange} />
      </div>

      {children}
    </div>
  );
}

function CourseDetailTabs<T extends string>({
  tabs,
  tab,
  onChange,
}: {
  tabs: CourseDetailTab<T>[];
  tab: T;
  onChange: (tab: T) => void;
}) {
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    onChange(tabs[next].value);
    document.getElementById(`course-detail-tab-${tabs[next].value}`)?.focus();
  };

  return (
    <div role="tablist" aria-label="Course sections" className="flex flex-nowrap overflow-x-auto border-t border-border px-2">
      {tabs.map((item, index) => {
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

/** Course content tab: `GET /courses/{id}/content` (participant or server-checked staff/teacher preview). */
export function CourseContentTab({
  courseId,
  expanded,
  onToggle,
  launchingId,
  onLaunch,
  onPayPeriod,
  onPayLesson,
  denied,
  showProgress,
  emptyMessage,
}: {
  courseId: string;
  expanded: ReadonlySet<string>;
  onToggle: (id: string) => void;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayPeriod: ((period: PeriodNode) => void) | null;
  onPayLesson: ((lesson: LessonNode) => void) | null;
  /** Rendered when the server answers 403/404 for the content tree. */
  denied: ReactNode;
  showProgress?: boolean;
  emptyMessage?: string;
}) {
  const query = useCourseContent(courseId);
  if (query.status === "pending") return <LoadingState label="Loading course content…" />;
  if (query.status === "error") {
    if (isApiClientError(query.error) && (query.error.status === 404 || query.error.status === 403)) {
      return <>{denied}</>;
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
      showProgress={showProgress}
      emptyMessage={emptyMessage}
    />
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

export function CourseAboutPanel({ course }: { course: StudentCourseDetail }) {
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
