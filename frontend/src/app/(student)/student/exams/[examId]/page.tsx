"use client";

import { useParams } from "next/navigation";
import { StudentExamStart } from "@/components/exams/student/student-exam-start";

/** Exam details + start (Slice C): rules summary, password, Start / Resume. */
export default function StudentExamDetailsPage() {
  const { examId } = useParams<{ examId: string }>();
  return <StudentExamStart examId={examId} />;
}
