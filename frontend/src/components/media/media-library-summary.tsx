"use client";

import { FileImage, FileText, History, PlayCircle } from "lucide-react";
import { useMediaList } from "@/lib/api/media";
import { useMediaFileSummary } from "@/lib/api/media-files";
import { classifyQueryError } from "@/lib/api/query-status";

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

const chipClass =
  "inline-flex items-center gap-1.5 rounded-full bg-background/80 px-3 py-1 text-xs font-medium text-foreground ring-1 ring-border";

/**
 * The Klass summary card: "N items - everything this institute can attach to
 * a session", with video / document / image chips. Videos come from the video
 * list's `totalElements` (what the caller can see); documents and images from
 * `GET /media-files/summary`. Counts are informational only.
 */
export function MediaLibrarySummary() {
  const summary = useMediaFileSummary();
  const videos = useMediaList({ page: 0, size: 1 });
  const loading = summary.status === "pending" || videos.status === "pending";
  const failed = summary.status === "error" || videos.status === "error";
  const forbidden =
    (summary.status === "error" && classifyQueryError(summary.error) === "forbidden") ||
    (videos.status === "error" && classifyQueryError(videos.error) === "forbidden");

  const videoCount = videos.data?.totalElements ?? 0;
  const documents = summary.data?.documents ?? 0;
  const images = summary.data?.images ?? 0;
  const total = (summary.data?.total ?? 0) + videoCount;
  const updatedAt = Math.max(summary.dataUpdatedAt, videos.dataUpdatedAt);

  return (
    <section
      aria-labelledby="media-summary-heading"
      className="rounded-xl border border-border bg-gradient-to-br from-primary/10 via-muted/40 to-background p-5"
      data-testid="media-library-summary"
    >
      <p className="text-xs font-medium tracking-wide text-muted-foreground">Media library</p>
      <h2 id="media-summary-heading" className="mt-1 text-3xl font-semibold text-foreground" aria-busy={loading}>
        {loading ? (
          <span className="inline-block h-8 w-28 animate-pulse rounded bg-muted" aria-label="Loading item count" />
        ) : failed ? (
          <span className="text-base font-medium text-muted-foreground">
            {forbidden ? "Item counts aren't available to you" : "Couldn't load item counts"}
          </span>
        ) : (
          <span data-testid="media-summary-total">{plural(total, "item", "items")}</span>
        )}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">Everything this institute can attach to a session</p>
      {!loading && !failed ? (
        <ul aria-label="Item counts by type" className="mt-3 flex flex-wrap items-center gap-2">
          <li className={chipClass} data-testid="media-summary-videos">
            <PlayCircle aria-hidden="true" className="size-3.5" />
            {plural(videoCount, "video", "videos")}
          </li>
          <li className={chipClass} data-testid="media-summary-documents">
            <FileText aria-hidden="true" className="size-3.5" />
            {plural(documents, "document", "documents")}
          </li>
          <li className={chipClass} data-testid="media-summary-images">
            <FileImage aria-hidden="true" className="size-3.5" />
            {plural(images, "image", "images")}
          </li>
          {updatedAt > 0 ? (
            <li className="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <History aria-hidden="true" className="size-3.5" />
              Updated {new Intl.DateTimeFormat("en-US", { timeStyle: "short" }).format(new Date(updatedAt))}
            </li>
          ) : null}
        </ul>
      ) : null}
    </section>
  );
}
