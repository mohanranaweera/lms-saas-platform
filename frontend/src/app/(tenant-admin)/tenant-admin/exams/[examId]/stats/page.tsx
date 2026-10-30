"use client";

import { useParams } from "next/navigation";
import { ExamStatsPage } from "@/components/exams/stats/exam-stats-page";

/** Exam Stats (Slice E): summary tiles and, for MCQ, the question distribution and breakdown. */
export default function TenantAdminExamStatsPage() {
  const params = useParams<{ examId: string }>();
  return <ExamStatsPage portal="tenant-admin" examId={params.examId} />;
}
