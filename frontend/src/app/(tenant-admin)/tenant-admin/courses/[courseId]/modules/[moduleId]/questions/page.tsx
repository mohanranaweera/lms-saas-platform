"use client";

import { Suspense } from "react";
import { useParams } from "next/navigation";
import { ModuleQuestionBank } from "@/components/exams/bank/module-question-bank";

/** "Questions in {module}" — the module's question bank (Klass Exam Module, Slice A). */
export default function TenantAdminModuleQuestionsPage() {
  const params = useParams<{ courseId: string; moduleId: string }>();
  return (
    <Suspense>
      <ModuleQuestionBank portal="tenant-admin" courseId={params.courseId} moduleId={params.moduleId} />
    </Suspense>
  );
}
