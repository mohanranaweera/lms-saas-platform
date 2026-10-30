"use client";

import { useParams } from "next/navigation";
import { QuestionEditorPage } from "@/components/exams/bank/question-editor-page";

/** Edit one module-bank question (Klass Exam Module, Slice A). */
export default function TenantAdminEditQuestionPage() {
  const params = useParams<{ courseId: string; moduleId: string; questionId: string }>();
  return (
    <QuestionEditorPage
      portal="tenant-admin"
      courseId={params.courseId}
      moduleId={params.moduleId}
      questionId={params.questionId}
    />
  );
}
