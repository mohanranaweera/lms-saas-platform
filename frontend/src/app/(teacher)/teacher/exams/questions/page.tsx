"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LiveRegion } from "@/components/ui/live-region";
import { QuestionCreateForm } from "@/components/exams/question-create-form";
import { QuestionListItem } from "@/components/exams/question-list-item";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams } from "@/lib/auth/permissions";
import { useCourses } from "@/lib/api/courses";
import { useCourseQuestions, useCreateDraftExam } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";

const PAGE_SIZE = 20;

/**
 * Teacher (+Teacher Assistant, tenant-wide) Question Bank (MVP-017 plan §11
 * screen #4). Course selector first (own courses for a Teacher, tenant-wide
 * for TA/staff — enforced server-side, this hook just reflects it), then
 * list/create/edit/delete for the selected course.
 *
 * Also exposes "Create a new exam" for the selected course — the only
 * backend-supported entry point into an exam's own id (there is no
 * list-exams-by-course endpoint, see `lib/api/exams.ts`'s doc comment), so
 * creating an exam here and immediately navigating to its Scheduler page
 * (`/teacher/exams/{examId}/schedule`) is how a Teacher reaches that screen.
 */
export default function TeacherQuestionBankPage() {
  const router = useRouter();
  const { session } = useAuth();
  const canAuthor = canAuthorExams(session?.role ?? null);
  const coursesQuery = useCourses();
  const [courseId, setCourseId] = useState<string>("");
  const [page, setPage] = useState(0);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createExamError, setCreateExamError] = useState<string | null>(null);

  const questionsQuery = useCourseQuestions(courseId, { page, size: PAGE_SIZE });
  const createExamMutation = useCreateDraftExam(courseId);

  function handleCourseChange(value: string) {
    setCourseId(value);
    setPage(0);
  }

  const handleCreateExam = async () => {
    setCreateExamError(null);
    try {
      const exam = await createExamMutation.mutateAsync({ title: "Untitled exam" });
      router.push(`/teacher/exams/${exam.id}/schedule`);
    } catch (error) {
      setCreateExamError(isApiClientError(error) ? error.message : "Could not create a new exam. Please try again.");
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Question Bank</h1>
        <p className="text-sm text-muted-foreground">
          Author reusable multiple-choice and structured questions for a course, then assemble them into an
          exam from the Scheduler.
        </p>
      </div>

      <QueryStateBoundary
        query={coursesQuery}
        loadingLabel="Loading your courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No courses yet",
          description: "You need at least one course before you can author exam questions.",
          action: { label: "Create a course", onClick: () => router.push("/teacher/courses/new") },
        }}
      >
        {(courses) => (
          <div className="flex flex-col gap-1.5 sm:max-w-sm">
            <Label htmlFor="question-bank-course">Course</Label>
            <Select value={courseId} onValueChange={(value) => value && handleCourseChange(value)}>
              <SelectTrigger id="question-bank-course" className="w-full">
                <SelectValue placeholder="Select a course" />
              </SelectTrigger>
              <SelectContent>
                {courses.content.map((course) => (
                  <SelectItem key={course.id} value={course.id}>
                    {course.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
      </QueryStateBoundary>

      {courseId ? (
        <>
          {canAuthor ? (
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <Button type="button" onClick={() => setShowCreateForm((v) => !v)} className="w-full sm:w-fit">
                {showCreateForm ? "Close" : "Add question"}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={handleCreateExam}
                disabled={createExamMutation.isPending}
                aria-busy={createExamMutation.isPending}
                className="w-full sm:w-fit"
              >
                {createExamMutation.isPending ? "Creating exam…" : "Create a new exam for this course"}
              </Button>
            </div>
          ) : null}
          <LiveRegion message={createExamMutation.isPending ? "Creating a new exam…" : ""} />
          {createExamError ? (
            <p role="alert" className="text-sm text-destructive">
              {createExamError}
            </p>
          ) : null}

          {showCreateForm && canAuthor ? (
            <div className="rounded-lg border border-border p-4">
              <QuestionCreateForm courseId={courseId} onCreated={() => setShowCreateForm(false)} />
            </div>
          ) : null}

          <QueryStateBoundary
            query={questionsQuery}
            loadingLabel="Loading the question bank…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
            isEmpty={(data) => data.content.length === 0}
            emptyState={
              canAuthor
                ? {
                    title: "No questions in the bank yet",
                    description: "Add your first multiple-choice or structured question for this course.",
                    action: { label: "Add your first question", onClick: () => setShowCreateForm(true) },
                  }
                : {
                    title: "No questions in the bank yet",
                    description: "This course has no exam questions yet. You do not have permission to add any here.",
                  }
            }
          >
            {(data) => (
              <div className="flex flex-col gap-4">
                <ul className="flex flex-col gap-3">
                  {data.content.map((question) => (
                    <QuestionListItem key={question.id} courseId={courseId} question={question} />
                  ))}
                </ul>
                <div className="flex items-center justify-between">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((current) => Math.max(0, current - 1))}
                    disabled={page === 0}
                  >
                    Previous
                  </Button>
                  <span className="text-xs text-muted-foreground">
                    Page {data.page + 1} of {Math.max(data.totalPages, 1)}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((current) => current + 1)}
                    disabled={data.page + 1 >= data.totalPages}
                  >
                    Next
                  </Button>
                </div>
              </div>
            )}
          </QueryStateBoundary>
        </>
      ) : null}
    </div>
  );
}
