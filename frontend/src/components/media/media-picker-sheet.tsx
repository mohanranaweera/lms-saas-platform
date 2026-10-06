"use client";

import { useDeferredValue, useId, useMemo, useState } from "react";
import { Film, Loader2, Search, Video } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  useAttachLessonMedia,
  useMediaList,
  type LessonMedia,
  type MediaAsset,
} from "@/lib/api/media";
import { formatDuration } from "@/lib/media-format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";

/**
 * VIDEO Phase 6: choose an already-ready Media Library asset for one lesson.
 * The backend remains authoritative for tenant visibility and attachment
 * authorization; the picker only filters the response for a useful UX.
 */
export function MediaPickerSheet({
  open,
  onOpenChange,
  courseId,
  lessonId,
  attached,
  onAttached,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  courseId: string;
  lessonId: string;
  attached: LessonMedia[];
  onAttached?: (media: LessonMedia) => void;
}) {
  const searchId = useId();
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const mediaQuery = useMediaList({ q: deferredSearch, status: "READY", size: 100 }, open);
  const attachMutation = useAttachLessonMedia(courseId, lessonId);
  const attachedIds = useMemo(() => new Set(attached.map((item) => item.mediaAssetId)), [attached]);

  async function attach(asset: MediaAsset) {
    try {
      const lessonMedia = await attachMutation.mutateAsync({ mediaAssetId: asset.id });
      onAttached?.(lessonMedia);
      onOpenChange(false);
    } catch {
      // The mutation error is rendered below; no optimistic authorization is attempted.
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-xl">
        <SheetHeader className="border-b border-border">
          <SheetTitle>Choose lesson video</SheetTitle>
          <SheetDescription>
            Select a ready video from the Media Library. Access and tenant permissions are checked by the server.
          </SheetDescription>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={searchId}>Search ready videos</Label>
            <div className="relative">
              <Search aria-hidden="true" className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
              <Input
                id={searchId}
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search by title or filename"
                className="pl-9"
              />
            </div>
          </div>

          {attachMutation.isError ? (
            <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
              {isApiClientError(attachMutation.error)
                ? attachMutation.error.message
                : "Couldn’t attach this video. Please try again."}
            </div>
          ) : null}

          <QueryStateBoundary
            query={mediaQuery}
            loadingLabel="Loading ready videos…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
            isEmpty={(data) => data.content.length === 0}
            emptyState={
              search.trim()
                ? {
                    title: "No ready videos match",
                    description: "Try a different title or clear the search.",
                    action: { label: "Clear search", onClick: () => setSearch("") },
                  }
                : {
                    title: "No ready videos yet",
                    description: "Upload a video to the Media Library and wait for it to become ready before attaching it.",
                  }
            }
          >
            {(data) => (
              <ul aria-label="Ready media" className="flex flex-col gap-2">
                {data.content.map((asset) => {
                  const alreadyAttached = attachedIds.has(asset.id);
                  return (
                    <li key={asset.id} className="flex items-center gap-3 rounded-md border border-border p-3">
                      <div className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded bg-muted">
                        {asset.thumbnailUrl ? (
                          // eslint-disable-next-line @next/next/no-img-element -- provider thumbnail already authorized by Media Library
                          <img src={asset.thumbnailUrl} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <Film aria-hidden="true" className="size-6 text-muted-foreground" />
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate font-medium text-foreground">{asset.title}</p>
                        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <Badge variant="outline">{asset.provider}</Badge>
                          <Badge variant="outline">Ready</Badge>
                          {asset.durationSeconds !== null ? <span>{formatDuration(asset.durationSeconds)}</span> : null}
                        </div>
                      </div>
                      <Button
                        type="button"
                        size="sm"
                        variant={alreadyAttached ? "outline" : "default"}
                        disabled={alreadyAttached || attachMutation.isPending}
                        aria-busy={attachMutation.isPending && attachMutation.variables?.mediaAssetId === asset.id}
                        onClick={() => void attach(asset)}
                      >
                        {attachMutation.isPending && attachMutation.variables?.mediaAssetId === asset.id ? (
                          <><Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> Attaching…</>
                        ) : alreadyAttached ? (
                          "Attached"
                        ) : (
                          <><Video aria-hidden="true" /> Add</>
                        )}
                      </Button>
                    </li>
                  );
                })}
              </ul>
            )}
          </QueryStateBoundary>
        </div>
      </SheetContent>
    </Sheet>
  );
}
