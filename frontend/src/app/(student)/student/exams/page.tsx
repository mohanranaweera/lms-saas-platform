"use client";

import { StudentExamList } from "@/components/exams/student/student-exam-list";

/**
 * Student "My exams" (Klass Exam Module, Slice C). Replaces the MVP-017 list:
 * only exams the student is exam-enrolled in (`GET /student/exams`), grouped by
 * the server clock.
 */
export default function StudentExamsPage() {
  return <StudentExamList />;
}
