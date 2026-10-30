"use client";

import { useMemo } from "react";
import "katex/dist/katex.min.css";
import { prepareDisplayHtml } from "@/lib/exams/rich-content";
import { cn } from "@/lib/utils";

/**
 * Read-only renderer for exam rich content (question body, answer option,
 * explanation). The HTML comes from the server already sanitized; it is run
 * through the same allowlist again here, math nodes are rendered with KaTeX
 * from `data-latex`, and image `src` is only ever the server-provided signed
 * URL.
 */
export function RichContent({
  html,
  className,
  emptyText,
  testId,
}: {
  html: string | null | undefined;
  className?: string;
  /** Rendered (muted) when there is no content. */
  emptyText?: string;
  testId?: string;
}) {
  const display = useMemo(() => prepareDisplayHtml(html), [html]);
  if (!display) {
    return emptyText ? (
      <p className={cn("text-sm text-muted-foreground", className)} data-testid={testId}>
        {emptyText}
      </p>
    ) : null;
  }
  return (
    <div
      className={cn("rich-content min-w-0 break-words", className)}
      data-testid={testId}
      // Sanitized twice (server jsoup + client allowlist) — see prepareDisplayHtml.
      dangerouslySetInnerHTML={{ __html: display }}
    />
  );
}
