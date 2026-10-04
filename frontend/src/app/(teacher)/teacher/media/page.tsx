"use client";

import { MediaLibrary } from "@/components/media/media-library";

/** Teacher → Media Library (VIDEO Phase 5): the teacher's own uploads (Q-010). */
export default function TeacherMediaPage() {
  return (
    <MediaLibrary
      basePath="/teacher/media"
      dashboardHref="/teacher/dashboard"
      canUpload
      scopeNote="Videos you have uploaded to your institute's YouTube channel."
    />
  );
}
