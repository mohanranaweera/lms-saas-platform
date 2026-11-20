"use client";

import { useParams, useSearchParams } from "next/navigation";
import { StudentExamResultPage, type FinishedReason } from "@/components/exams/student/student-exam-result";

const REASONS: FinishedReason[] = ["submitted", "timeout", "closed"];

/** Exam result (Slice C). Replaces the MVP-017 "Results & Review" page. */
export default function StudentExamResultsPage() {
  const { examId } = useParams<{ examId: string }>();
  const finishedParam = useSearchParams().get("finished");
  const finished = REASONS.find((reason) => reason === finishedParam) ?? null;
  return <StudentExamResultPage examId={examId} finished={finished} />;
}
