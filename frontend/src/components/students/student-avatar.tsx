"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

export function initialsOf(name: string | null | undefined): string {
  if (!name) return "";
  return name
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();
}

const SIZES = {
  sm: "size-8 text-xs",
  md: "size-12 text-sm",
  lg: "size-20 text-xl",
  xl: "size-28 text-2xl",
} as const;

/**
 * Circular student avatar (STU-MGMT-02 §6): the signed photo URL when there
 * is one, otherwise the student's initials. A broken/expired URL falls back
 * to initials too, never a broken-image icon.
 */
export function StudentAvatar({
  name,
  photoUrl,
  size = "md",
  className,
}: {
  name: string | null | undefined;
  photoUrl?: string | null;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const showPhoto = Boolean(photoUrl) && failedUrl !== photoUrl;
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary/10 font-semibold text-primary",
        SIZES[size],
        className
      )}
      data-testid="student-avatar"
    >
      {showPhoto ? (
        // eslint-disable-next-line @next/next/no-img-element -- short-lived signed URL; next/image would proxy/cache it
        <img
          src={photoUrl!}
          alt={name ? `${name} profile photo` : "Profile photo"}
          className="size-full object-cover"
          onError={() => setFailedUrl(photoUrl ?? null)}
        />
      ) : (
        <span aria-hidden="true">{initialsOf(name) || "?"}</span>
      )}
    </span>
  );
}
