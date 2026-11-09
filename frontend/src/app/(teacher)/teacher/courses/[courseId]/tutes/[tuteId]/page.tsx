"use client";

import { useParams } from "next/navigation";
import { TuteDetailPage } from "@/components/tutes/tute-detail-page";
import { TEACHER_TUTE_ROUTES } from "@/components/tutes/tute-routes";

/** TUTE-01 tute detail (stock, assigned students). Access is decided by the backend (TUTE_VIEW_TUTE + course scope). */
export default function TeacherTuteDetailPage() {
  const params = useParams<{ courseId: string; tuteId: string }>();
  return <TuteDetailPage courseId={params.courseId} tuteId={params.tuteId} routes={TEACHER_TUTE_ROUTES} />;
}
