"use client";

import { useParams } from "next/navigation";
import { ExamHeaderPage } from "@/components/exams/config/exam-placeholder-pages";

/** Exam statistics — header only in Slice B; Slice E adds the statistics. */
export default function TenantAdminExamStatsPage() {
  const params = useParams<{ examId: string }>();
  return <ExamHeaderPage portal="tenant-admin" examId={params.examId} view="stats" />;
}
