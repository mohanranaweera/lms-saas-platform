"use client";

import { useParams } from "next/navigation";
import { BroadcastDetail } from "@/components/communications/broadcast-detail";

/** Teacher → Message detail (Wave 11). Another sender's broadcast is a 404 (backend). */
export default function TeacherMessageDetailPage() {
  const params = useParams<{ broadcastId: string }>();
  return <BroadcastDetail broadcastId={params.broadcastId ?? ""} backHref="/teacher/communications" />;
}
