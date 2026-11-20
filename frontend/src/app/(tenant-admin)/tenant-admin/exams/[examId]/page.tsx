"use client";

import { useParams } from "next/navigation";
import { ExamManagementPage } from "@/components/exams/management/exam-management-page";

/** Manage exam (Slice D): header, participation summary, enrolled students, marking. */
export default function TenantAdminExamManagePage() {
  const params = useParams<{ examId: string }>();
  return <ExamManagementPage portal="tenant-admin" examId={params.examId} />;
}
