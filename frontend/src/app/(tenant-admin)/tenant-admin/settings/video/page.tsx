"use client";

import { Suspense } from "react";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { LoadingState } from "@/components/states/loading-state";
import { VideoProvidersSection } from "./video-providers-section";
import { VideoSettingsSection } from "./video-settings-section";

/**
 * Tenant Admin → Settings → Video: video providers (where lesson videos are
 * stored, VIDEO Phase 3 / ADR-018 Amendment 2) and video settings (upload and
 * playback rules) on one tab. The old `/settings/video-providers` and
 * `/settings/video-configuration` routes redirect here; the YouTube connect
 * callback still lands on `/video-providers?pending=…` and is forwarded with
 * its query intact.
 */
export default function VideoSettingsPage() {
  return (
    <div className="flex flex-col gap-8">
      <SettingsHeader
        active="/tenant-admin/settings/video"
        description="Where your institute's lesson videos are stored, and how they are uploaded and played."
      />
      {/* useSearchParams (YouTube connect result) needs a Suspense boundary. */}
      <Suspense fallback={<LoadingState label="Loading video providers…" />}>
        <VideoProvidersSection />
      </Suspense>
      <div className="border-t border-border pt-8">
        <VideoSettingsSection />
      </div>
    </div>
  );
}
