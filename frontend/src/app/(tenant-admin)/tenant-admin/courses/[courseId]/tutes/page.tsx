"use client";

import { useParams } from "next/navigation";
import { TuteListPage } from "@/components/tutes/tute-list-page";
import { TENANT_ADMIN_TUTE_ROUTES } from "@/components/tutes/tute-routes";

/** TUTE-01 Course → Manage Tutes. Access is decided by the backend (TUTE_VIEW_TUTES + course scope). */
export default function TenantAdminCourseTutesPage() {
  const params = useParams<{ courseId: string }>();
  return <TuteListPage courseId={params.courseId} routes={TENANT_ADMIN_TUTE_ROUTES} />;
}
