"use client";

import { Skeleton } from "@/components/ui/skeleton";
import { SecureVideoPlayer } from "@/components/courses/secure-video-player";
import { useMediaAsset } from "@/lib/api/media";
import { safeYouTubeEmbedUrl } from "@/lib/validation/appearance";

/**
 * Course-builder video preview for staff, routed by the video's provider.
 * Institute-storage and DynTube videos use the secure player as before.
 * YouTube videos can't use that pipeline (students watch them only in the
 * AOC Player, ADR-025 Amendment 3), so staff preview them from the YouTube
 * link the server already reveals to them in the Media Library. If the server
 * doesn't reveal the link to this caller, nothing is embedded.
 */
export function StaffVideoPreview({ videoAssetId, title }: { videoAssetId: string; title: string }) {
  const asset = useMediaAsset(videoAssetId);
  if (asset.isPending) return <Skeleton className="aspect-video w-full rounded-md" aria-label="Loading video preview" />;
  if (asset.data?.provider !== "YOUTUBE") {
    return <SecureVideoPlayer videoAssetId={videoAssetId} title={title} />;
  }
  const embedUrl = safeYouTubeEmbedUrl(asset.data.youtubeUrl);
  if (!embedUrl) {
    return (
      <p role="status" className="rounded-md border border-border bg-muted/40 px-4 py-6 text-center text-sm text-muted-foreground">
        A preview isn&apos;t available for this YouTube video. Students watch it in the AOC Player.
      </p>
    );
  }
  return (
    <div className="relative aspect-video w-full overflow-hidden rounded-md bg-black" data-testid="youtube-staff-preview">
      <iframe
        title={`Preview: ${title}`}
        src={embedUrl}
        className="absolute inset-0 size-full"
        allow="encrypted-media; picture-in-picture; fullscreen"
        referrerPolicy="strict-origin-when-cross-origin"
        allowFullScreen
      />
    </div>
  );
}
