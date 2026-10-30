"use client";

import { useParams } from "next/navigation";
import { StudentExamTake } from "@/components/exams/student/student-exam-take";

/**
 * Exam taking (Slice C). Replaces the MVP-017 take page: the attempt is
 * started on the details page (password) and resumed here from the server
 * (`GET /student/attempts/{id}`), so a refresh never loses saved answers.
 */
export default function StudentExamTakePage() {
  const { examId } = useParams<{ examId: string }>();
  return <StudentExamTake examId={examId} />;
}
