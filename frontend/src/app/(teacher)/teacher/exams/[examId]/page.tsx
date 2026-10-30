"use client";

import { useParams } from "next/navigation";
import { ExamHeaderPage } from "@/components/exams/config/exam-placeholder-pages";

/** Manage exam — header only in Slice B; Slice D adds student management. */
export default function TeacherExamManagePage() {
  const params = useParams<{ examId: string }>();
  return <ExamHeaderPage portal="teacher" examId={params.examId} view="manage" />;
}
