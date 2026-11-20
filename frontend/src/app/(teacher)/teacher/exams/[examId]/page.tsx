"use client";

import { useParams } from "next/navigation";
import { ExamManagementPage } from "@/components/exams/management/exam-management-page";

/** Manage exam (Slice D): header, participation summary, enrolled students, marking. */
export default function TeacherExamManagePage() {
  const params = useParams<{ examId: string }>();
  return <ExamManagementPage portal="teacher" examId={params.examId} />;
}
