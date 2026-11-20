"use client";

import { useParams } from "next/navigation";
import { MediaDetail } from "@/components/media/media-detail";

/** Teacher → Media Library → one of their videos (VIDEO Phase 5). */
export default function TeacherMediaDetailPage() {
  const { mediaId } = useParams<{ mediaId: string }>();
  return (
    <MediaDetail mediaId={mediaId} listHref="/teacher/media" dashboardHref="/teacher/dashboard" canManage />
  );
}
