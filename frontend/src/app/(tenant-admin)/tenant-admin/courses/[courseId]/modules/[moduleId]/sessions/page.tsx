"use client";

import { useParams } from "next/navigation";
import { SessionsPanel } from "@/components/courses/admin/sessions-panel";

/** Tenant Admin Sessions of one module (Klass parity, CM-A04/A05). */
export default function TenantAdminModuleSessionsPage() {
  const params = useParams<{ courseId: string; moduleId: string }>();
  return <SessionsPanel courseId={params.courseId} moduleId={params.moduleId} />;
}
