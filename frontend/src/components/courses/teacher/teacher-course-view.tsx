"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Info, MonitorPlay, Video } from "lucide-react";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { isApiClientError } from "@/lib/api/error";
import { useStudentCourse, type LessonNode, type StudentCourseDetail } from "@/lib/api/klass-student";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import {
  CourseAboutPanel,
  CourseContentTab,
  CourseDetailFrame,
  type CourseDetailTab,
} from "@/components/courses/student/course-detail-layout";
import { CourseLiveClassesTab } from "@/components/courses/student/course-live-classes-tab";
import { TeacherCourseManageMenu } from "./teacher-course-manage-menu";

type TeacherTab = "content" | "live" | "about";

const TABS: CourseDetailTab<TeacherTab>[] = [
  { value: "content", label: "Course content", icon: <MonitorPlay className="size-4" aria-hidden="true" /> },
  { value: "live", label: "Live Classes", icon: <Video className="size-4" aria-hidden="true" /> },
  { value: "about", label: "About this course", icon: <Info className="size-4" aria-hidden="true" /> },
];

function tabFromParam(value: string | null): TeacherTab {
  return TABS.some((tab) => tab.value === value) ? (value as TeacherTab) : "content";
}

const NO_LAUNCH_ID = null;

/**
 * Teacher Portal course view (TP-02): the student course layout (shared
 * `CourseDetailFrame`), fed by a teacher data/action model. Access comes from
 * the caller's course assignment, enforced by the server on `GET
 * /courses/{id}` and the `/content` / `/live-classes` teacher preview. No
 * enrollment, checkout, payment, launch (progress) or attendance call is made
 * here: opening a lesson goes to its materials, served through the
 * assignment-checked material/video guards.
 */
export function TeacherCourseView({ courseId }: { courseId: string }) {
  const courseQuery = useStudentCourse(courseId);

  if (courseQuery.status === "pending") return <LoadingState label="Loading course…" />;
  if (courseQuery.status === "error") {
    const status = isApiClientError(courseQuery.error) ? courseQuery.error.status : undefined;
    if (status === 403 || status === 404) return <CourseUnavailable />;
    return (
      <ErrorState
        message="We couldn't load this course."
        code={isApiClientError(courseQuery.error) ? courseQuery.error.code : undefined}
        onRetry={() => courseQuery.refetch()}
      />
    );
  }
  return <TeacherCourseLoaded course={courseQuery.data} />;
}

function CourseUnavailable() {
  return (
    <div data-testid="teacher-course-unavailable">
      <EmptyState
        title="This course isn't available"
        description="You aren't assigned to this course, or it no longer exists. Go back to My Courses to find your courses."
      />
      <div className="mt-4 text-center">
        <Link href="/teacher/courses" className="text-sm font-medium text-blue-700 hover:underline">
          Back to My Courses
        </Link>
      </div>
    </div>
  );
}

function TeacherCourseLoaded({ course }: { course: StudentCourseDetail }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tab = tabFromParam(searchParams.get("tab"));
  const toast = useAdminToast();

  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const toggleFolder = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const setTab = (next: TeacherTab) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === "content") params.delete("tab");
    else params.set("tab", next);
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  // A linked SOURCE course's lesson opens under its own course id (the server checks that assignment).
  const openLesson = (lesson: LessonNode) =>
    router.push(
      `/teacher/courses/${lesson.courseId}/modules/${lesson.moduleId}/lessons/${lesson.lessonId}/materials`
    );

  const denied = <CourseUnavailable />;

  return (
    <CourseDetailFrame
      course={course}
      backHref="/teacher/courses"
      backLabel="My Courses"
      headerCta={
        <TeacherCourseManageMenu
          courseId={course.id}
          courseName={course.name}
          onScheduleSaved={() => toast.show({ kind: "success", text: `Class schedule saved for “${course.name}”.` })}
        />
      }
      tabs={TABS}
      tab={tab}
      onTabChange={setTab}
      testId="teacher-course-view"
    >
      <p className="-mt-2 text-xs text-muted-foreground" data-testid="teacher-course-preview-note">
        Teacher view: students see this layout, with payment prompts and progress shown only to them.
      </p>
      <div role="tabpanel" id="course-detail-tabpanel" aria-labelledby={`course-detail-tab-${tab}`}>
        {tab === "content" ? (
          <CourseContentTab
            courseId={course.id}
            expanded={expanded}
            onToggle={toggleFolder}
            launchingId={NO_LAUNCH_ID}
            onLaunch={openLesson}
            onPayPeriod={null}
            onPayLesson={null}
            denied={denied}
            showProgress={false}
            emptyMessage="This course has no modules or sessions yet. Use Manage course → Modules & sessions to add them."
          />
        ) : tab === "live" ? (
          <CourseLiveClassesTab
            courseId={course.id}
            timeZone={course.timezone ?? null}
            launchingId={NO_LAUNCH_ID}
            onLaunch={openLesson}
            onPayLesson={null}
            notEnrolled={denied}
          />
        ) : (
          <CourseAboutPanel course={course} />
        )}
      </div>
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </CourseDetailFrame>
  );
}
