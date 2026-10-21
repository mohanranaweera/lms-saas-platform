"use client";

import { useState } from "react";
import { Eye, EyeOff, Loader2, Trash2 } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  useDetachLessonMedia,
  useLessonMedia,
  useUpdateLessonMedia,
  type LessonMedia,
  type LessonMediaVisibility,
} from "@/lib/api/media";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { formatDuration } from "@/lib/media-format";
import { VideoWatchTimeLimitControl } from "@/components/media/watch-time-limit";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertDialog, AlertDialogClose, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** Server-backed management list for video assets attached through LessonMedia. */
export function LessonMediaAttachments({
  courseId,
  lessonId,
}: {
  courseId: string;
  lessonId: string;
}) {
  const query = useLessonMedia(courseId, lessonId);
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading attached lesson videos…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      isEmpty={(items) => items.length === 0}
      emptyState={{
        title: "No Media Library videos attached",
        description: "Choose a ready video from the Media Library to add one to this lesson.",
      }}
    >
      {(items) => (
        <div className="flex flex-col gap-2">
          <h5 className="text-xs font-medium text-foreground">Media Library videos</h5>
          {items.map((item) => (
            <LessonMediaAttachmentRow key={item.id} item={item} courseId={courseId} lessonId={lessonId} />
          ))}
        </div>
      )}
    </QueryStateBoundary>
  );
}

function LessonMediaAttachmentRow({
  item,
  courseId,
  lessonId,
}: {
  item: LessonMedia;
  courseId: string;
  lessonId: string;
}) {
  const update = useUpdateLessonMedia(courseId, lessonId);
  const detach = useDetachLessonMedia(courseId, lessonId);
  const [title, setTitle] = useState(item.title);
  const [visibility, setVisibility] = useState<LessonMediaVisibility>(item.visibility);
  const [detachOpen, setDetachOpen] = useState(false);
  const duration = formatDuration(item.durationSeconds);
  const error = update.error ?? detach.error;

  async function save() {
    await update.mutateAsync({
      lessonMediaId: item.id,
      input: { title: title.trim(), sequence: item.sequence, visibility },
    });
  }

  async function toggleVisibility() {
    const next = visibility === "VISIBLE" ? "HIDDEN" : "VISIBLE";
    setVisibility(next);
    try {
      await update.mutateAsync({
        lessonMediaId: item.id,
        input: { title: title.trim(), sequence: item.sequence, visibility: next },
      });
    } catch {
      setVisibility(item.visibility);
    }
  }

  async function remove() {
    try {
      await detach.mutateAsync(item.id);
      setDetachOpen(false);
    } catch {
      // The dialog stays open and renders the server error.
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-center">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <Badge variant="outline">{item.provider}</Badge>
          <Badge variant="outline">{item.status}</Badge>
          {duration ? <span className="text-xs text-muted-foreground">{duration}</span> : null}
        </div>
        <Label htmlFor={`lesson-media-title-${item.id}`} className="sr-only">Lesson video title</Label>
        <Input
          id={`lesson-media-title-${item.id}`}
          value={title}
          disabled={update.isPending || detach.isPending}
          onChange={(event) => setTitle(event.target.value)}
          onBlur={() => {
            if (title.trim() && title.trim() !== item.title) void save();
          }}
          className="mt-2 h-8"
        />
        <div className="mt-1.5">
          <VideoWatchTimeLimitControl videoAssetId={item.mediaAssetId} title={item.title} />
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          aria-label={visibility === "VISIBLE" ? `Hide ${item.title}` : `Show ${item.title}`}
          aria-busy={update.isPending}
          disabled={update.isPending || detach.isPending}
          onClick={() => void toggleVisibility()}
        >
          {visibility === "VISIBLE" ? <Eye aria-hidden="true" /> : <EyeOff aria-hidden="true" />}
        </Button>
        <AlertDialog open={detachOpen} onOpenChange={setDetachOpen}>
          <AlertDialogTrigger render={<Button type="button" variant="destructive" size="icon-sm" aria-label={`Remove ${item.title} from lesson`} disabled={update.isPending || detach.isPending} />}>
            <Trash2 aria-hidden="true" />
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Remove this video from the lesson?</AlertDialogTitle>
              <AlertDialogDescription>
                The video remains in the Media Library. Only this lesson attachment is removed.
              </AlertDialogDescription>
            </AlertDialogHeader>
            {error ? (
              <Alert variant="destructive"><AlertDescription>{isApiClientError(error) ? error.message : "Couldn’t update this lesson video."}</AlertDescription></Alert>
            ) : null}
            <AlertDialogFooter>
              <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
              <Button type="button" variant="destructive" onClick={() => void remove()} disabled={detach.isPending} aria-busy={detach.isPending}>
                {detach.isPending ? <><Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> Removing…</> : "Remove from lesson"}
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
      {error && !detachOpen ? (
        <div role="alert" className="text-xs text-destructive">
          {isApiClientError(error) ? error.message : "Couldn’t update this lesson video."}
        </div>
      ) : null}
    </div>
  );
}
