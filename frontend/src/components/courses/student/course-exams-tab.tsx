"use client";

import { useState } from "react";
import { ClipboardList } from "lucide-react";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { StudentExamCard } from "@/components/exams/student/student-exam-list";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import { useStudentExams, type StudentExam, type StudentExamList } from "@/lib/api/student-exams";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
type ExamBucket = "inProgress" | "upcoming" | "previous";

const BUCKET_LABELS: Record<ExamBucket, string> = {
  inProgress: "In progress",
  upcoming: "Upcoming",
  previous: "Previous",
};

const BUCKET_TEST_IDS: Record<ExamBucket, string> = {
  inProgress: "IN_PROGRESS",
  upcoming: "UPCOMING",
  previous: "PREVIOUS",
};

const EMPTY_COPY = (t: TranslateFn): Record<ExamBucket, { title: string; description: string }> => ({
  inProgress: {
    title: t("student.courseExamsTab.noExamsRunningRight"),
    description: t("student.courseExamsTab.whenAnExamYoure"),
  },
  upcoming: {
    title: t("student.courseExamsTab.noUpcomingExams"),
    description: t("student.courseExamsTab.examsOfThisCourse"),
  },
  previous: {
    title: t("student.courseExamsTab.noPreviousExams"),
    description: t("student.courseExamsTab.examsOfThisCourse2"),
  },
});

/**
 * `StudentExamResponse` has no `courseId` in the Slice C contract; match on it
 * when the server sends one, otherwise on the course name.
 */
function belongsTo(exam: StudentExam, courseId: string, courseName: string): boolean {
  if (exam.courseId) return exam.courseId === courseId;
  return exam.courseName === courseName;
}

/**
 * Exams tab of a student's course page: the student's exam-enrolled Klass
 * exams (`GET /student/exams`, grouped by the server clock) for this course.
 */
export function CourseExamsTab({ courseId, courseName }: { courseId: string; courseName: string }) {
  const t = useT();
  // Per-course list: never narrowed to a storefront, so this tab works for every joined course.
  const query = useStudentExams(courseId);
  const [bucket, setBucket] = useState<ExamBucket>("inProgress");

  if (query.status === "pending") return <LoadingState label={t("student.courseExamsTab.loadingExams")} />;
  if (query.status === "error") {
    return (
      <ErrorState
        message={t("student.courseExamsTab.weCouldntLoadYour")}
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  const data: StudentExamList = query.data;
  const byBucket = (key: ExamBucket) => data[key].filter((exam) => belongsTo(exam, courseId, courseName));
  const visible = byBucket(bucket);

  return (
    <section
      aria-label={t("student.courseExamsTab.exams")}
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6"
      data-testid="course-exams-panel"
    >
      <div role="group" aria-label={t("student.courseExamsTab.examStatus")} className="flex flex-wrap gap-2">
        {(Object.keys(BUCKET_LABELS) as ExamBucket[]).map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={bucket === value}
            onClick={() => setBucket(value)}
            data-testid={`exam-bucket-${BUCKET_TEST_IDS[value]}`}
            className={cn(
              "rounded-full border px-3 py-1 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
              bucket === value
                ? "border-primary bg-primary/10 text-primary"
                : "border-border text-foreground hover:bg-muted"
            )}
          >
            {value === "inProgress" ? t("exams.status.inProgress") : value === "upcoming" ? t("exams.status.upcoming") : t("student.courseExams.previous")} ({byBucket(value).length})
          </button>
        ))}
      </div>

      {visible.length === 0 ? (
        <div role="status" className="flex flex-col items-center gap-2 px-6 py-12 text-center" data-testid="course-exams-empty">
          <ClipboardList className="size-8 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm font-medium text-foreground">{EMPTY_COPY(t)[bucket].title}</p>
          <p className="text-sm text-muted-foreground">{EMPTY_COPY(t)[bucket].description}</p>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {visible.map((exam) => (
            <StudentExamCard key={exam.id} exam={exam} serverNow={data.serverNow} />
          ))}
        </div>
      )}
    </section>
  );
}
