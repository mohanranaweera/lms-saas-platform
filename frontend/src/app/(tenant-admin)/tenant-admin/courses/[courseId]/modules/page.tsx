"use client";

import { useParams } from "next/navigation";
import { ModulesPanel } from "@/components/courses/admin/modules-panel";

/**
 * Tenant Admin "Modules of <course>" (Klass parity, CM-A02/A03). The
 * pre-existing module/lesson/material editor moved to `../content` and is
 * linked from this page.
 */
export default function TenantAdminCourseModulesPage() {
  const params = useParams<{ courseId: string }>();
  return <ModulesPanel courseId={params.courseId} />;
}
