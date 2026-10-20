"use client";

import { MediaLibrary } from "@/components/media/media-library";

/** Teacher → Media Library (VIDEO Phase 5, Klass G1 R2-D1): every institute file and video; rename/delete own uploads only. */
export default function TeacherMediaPage() {
  return (
    <MediaLibrary
      basePath="/teacher/media"
      dashboardHref="/teacher/dashboard"
      canUpload
      scopeNote="Documents, images and videos your institute can attach to sessions. You can rename or delete the files you uploaded."
    />
  );
}
