"use client";

import { useState } from "react";
import { Clock } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { isApiClientError } from "@/lib/api/error";
import {
  useSetVideoWatchTimeLimit,
  useVideoWatchTimeLimit,
  type VideoWatchTimeLimit,
} from "@/lib/api/videos";
import { formatWatchSeconds } from "@/lib/api/watch-time";

/** Whole minutes 1-60000, or empty (= the institute rule). */
export function parseLimitMinutes(value: string): { ok: true; seconds: number | null } | { ok: false; message: string } {
  const trimmed = value.trim();
  if (trimmed === "") return { ok: true, seconds: null };
  if (!/^\d+$/.test(trimmed) || Number(trimmed) < 1 || Number(trimmed) > 60_000) {
    return { ok: false, message: "Enter whole minutes between 1 and 60000, or leave it empty." };
  }
  return { ok: true, seconds: Number(trimmed) * 60 };
}

export function describeLimit(limit: VideoWatchTimeLimit): string {
  if (limit.videoLimitSeconds !== null) return `${formatWatchSeconds(limit.videoLimitSeconds)} (this video)`;
  if (!limit.instituteDefaultKnown) return "Institute rule (needs the video's length)";
  if (limit.instituteDefaultSeconds === null) return "No limit (institute rule)";
  return `${formatWatchSeconds(limit.instituteDefaultSeconds)} (institute rule)`;
}

/**
 * VIDEO Phase 12: one lesson video's watch-time limit, with "Edit limit" for
 * whoever may change its playback rules. The video's own limit wins over the
 * institute rule; a change applies to every student from their next play.
 * The backend re-checks permission and records every change.
 */
export function VideoWatchTimeLimitControl({ videoAssetId, title }: { videoAssetId: string; title: string }) {
  const query = useVideoWatchTimeLimit(videoAssetId);
  const limit = query.data;
  // A role that may not see the rules gets 403 here: nothing is shown.
  if (query.isError) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <Clock aria-hidden="true" className="size-3.5" />
      <span>Watch-time limit: {limit ? describeLimit(limit) : "…"}</span>
      {limit ? <EditWatchTimeLimitDialog limit={limit} title={title} /> : null}
    </div>
  );
}

function EditWatchTimeLimitDialog({ limit, title }: { limit: VideoWatchTimeLimit; title: string }) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);
  const mutation = useSetVideoWatchTimeLimit();
  const inputId = `watch-time-limit-${limit.videoAssetId}`;
  const serverError = mutation.isError
    ? isApiClientError(mutation.error)
      ? (mutation.error.fieldErrors[0]?.message ?? mutation.error.message)
      : "Something went wrong. Please try again."
    : null;

  function save() {
    const parsed = parseLimitMinutes(value);
    if (!parsed.ok) {
      setInvalid(parsed.message);
      return;
    }
    setInvalid(null);
    mutation.mutate(
      { videoAssetId: limit.videoAssetId, limitSeconds: parsed.seconds },
      { onSuccess: () => setOpen(false) }
    );
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setValue(limit.videoLimitSeconds === null ? "" : String(Math.round(limit.videoLimitSeconds / 60)));
        } else {
          mutation.reset();
          setInvalid(null);
        }
      }}
    >
      <AlertDialogTrigger
        render={<Button type="button" variant="link" size="xs" aria-label={`Edit watch-time limit for ${title}`} />}
      >
        Edit limit
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Watch-time limit for {title}</AlertDialogTitle>
          <AlertDialogDescription>
            How long each student may watch this video. Set a limit here to override your institute&apos;s rule
            {limit.instituteDefaultSeconds !== null
              ? ` (${formatWatchSeconds(limit.instituteDefaultSeconds)})`
              : ""}
            ; leave it empty to follow the institute&apos;s rule. Changes apply to every student from their next play
            and are recorded.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {serverError ? (
          <Alert variant="destructive">
            <AlertDescription>{serverError}</AlertDescription>
          </Alert>
        ) : null}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={inputId}>Limit (minutes)</Label>
          <Input
            id={inputId}
            type="number"
            inputMode="numeric"
            min={1}
            step={1}
            placeholder="Institute rule"
            value={value}
            aria-invalid={invalid ? true : undefined}
            aria-describedby={invalid ? `${inputId}-error` : undefined}
            onChange={(event) => setValue(event.target.value)}
          />
          {invalid ? (
            <p id={`${inputId}-error`} role="alert" className="text-xs text-destructive">
              {invalid}
            </p>
          ) : null}
        </div>
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button type="button" onClick={save} disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Save limit"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
