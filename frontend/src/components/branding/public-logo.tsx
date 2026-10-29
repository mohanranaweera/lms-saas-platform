"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

export type LogoDisplaySize = "SMALL" | "STANDARD" | "LARGE";

export const LOGO_DISPLAY_SIZES: { value: LogoDisplaySize; label: string; dimensions: string }[] = [
  { value: "SMALL", label: "Small", dimensions: "44 px tall (36 px on mobile)" },
  { value: "STANDARD", label: "Standard", dimensions: "56 px tall (44 px on mobile)" },
  { value: "LARGE", label: "Large", dimensions: "68 px tall (52 px on mobile)" },
];

export function toLogoDisplaySize(value: unknown): LogoDisplaySize {
  return value === "SMALL" || value === "LARGE" ? value : "STANDARD";
}

function monogram(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  return (words.length === 1 ? words[0].slice(0, 2) : `${words[0][0]}${words[1][0]}`).toUpperCase();
}

/**
 * Tenant logo in a reserved box sized by the spec §4 tokens
 * (`.public-logo[data-size]` in globals.css: `--logo-h` / `--logo-max-w`,
 * mobile vs desktop via the nearest `.public-chrome` container). The image is
 * `h-full w-auto object-contain`, so wide and square logos both fit without
 * distortion or a tiny forced square. Without a logo (or if it fails to load)
 * a primary-colored monogram tile + the institute name is shown instead.
 */
export function PublicLogo({
  logoUrl,
  instituteName,
  size = "STANDARD",
  variant = "header",
  className,
}: {
  logoUrl: string | null | undefined;
  instituteName: string;
  size?: LogoDisplaySize;
  variant?: "header" | "footer";
  className?: string;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const name = instituteName.trim() || "Institute";
  const showImage = Boolean(logoUrl) && failedUrl !== logoUrl;

  if (showImage && logoUrl) {
    return (
      <span
        className={cn("public-logo inline-flex shrink-0 items-center", className)}
        data-size={size}
        data-variant={variant}
        data-testid={`public-logo-${variant}`}
      >
        {/* eslint-disable-next-line @next/next/no-img-element -- signed, short-lived tenant asset URL */}
        <img
          src={logoUrl}
          alt={name}
          className="block h-full w-auto max-w-(--logo-max-w) object-contain"
          onError={() => setFailedUrl(logoUrl)}
        />
      </span>
    );
  }

  return (
    <span
      className={cn("inline-flex min-w-0 items-center gap-2.5", className)}
      data-testid={`public-logo-${variant}`}
      data-fallback="monogram"
    >
      <span
        className="public-logo flex aspect-square shrink-0 items-center justify-center rounded-lg bg-primary text-sm font-bold text-primary-foreground"
        data-size={size}
        data-variant={variant}
        aria-hidden="true"
      >
        {monogram(name)}
      </span>
      <span className="font-heading truncate text-base font-semibold text-foreground sm:text-lg">{name}</span>
    </span>
  );
}
