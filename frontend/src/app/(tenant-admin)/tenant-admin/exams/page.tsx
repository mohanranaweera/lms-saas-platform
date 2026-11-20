"use client";

import { ExamDirectory } from "@/components/exams/config/exam-directory";

/**
 * Exams directory (Klass Exam Module, Slice B): in-progress / upcoming /
 * previous exams, search, and the shared create/edit dialog. The backend
 * scopes the list to the caller (a teacher only sees their own courses).
 */
export default function TenantAdminExamsPage() {
  return <ExamDirectory portal="tenant-admin" />;
}
