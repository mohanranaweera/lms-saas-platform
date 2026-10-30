"use client";

import { useParams } from "next/navigation";
import { QuestionEditorPage } from "@/components/exams/bank/question-editor-page";

/** "Add a Question to {course}" (Klass Exam Module, Slice A). */
export default function TenantAdminCreateQuestionPage() {
  const params = useParams<{ courseId: string; moduleId: string }>();
  return <QuestionEditorPage portal="tenant-admin" courseId={params.courseId} moduleId={params.moduleId} />;
}
