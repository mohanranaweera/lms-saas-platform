"use client";

import { useParams } from "next/navigation";
import { ExamHeaderPage } from "@/components/exams/config/exam-placeholder-pages";

/** Manage exam — header only in Slice B; Slice D adds student management. */
export default function TenantAdminExamManagePage() {
  const params = useParams<{ examId: string }>();
  return <ExamHeaderPage portal="tenant-admin" examId={params.examId} view="manage" />;
}
