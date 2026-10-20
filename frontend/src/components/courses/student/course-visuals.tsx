import { BookOpen, FileText, File, ImageIcon, Video, Clapperboard } from "lucide-react";
import { cn } from "@/lib/utils";
import { priceBadgeLabel, type PriceDisplay } from "@/lib/api/klass-student";
import { resourceKind, resourceLabel } from "./student-format";

/** Price chip: "LKR 4000 / month", "LKR 2000", "Free", "Charged per module". Renders nothing when unknown. */
export function PriceBadge({
  priceDisplay,
  pricingModel,
  className,
  testId = "course-price-badge",
}: {
  priceDisplay: PriceDisplay | null;
  pricingModel: string;
  className?: string;
  testId?: string;
}) {
  const label = priceBadgeLabel(priceDisplay, pricingModel);
  if (!label) return null;
  return (
    <span
      data-testid={testId}
      className={cn(
        "inline-flex w-fit items-center rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-blue-700 shadow-sm dark:bg-blue-950 dark:text-blue-200",
        className
      )}
    >
      {label}
    </span>
  );
}

/**
 * Course cover, or a neutral placeholder when the course has none. Signed,
 * short-lived URLs come from the server; a broken URL falls back to the
 * placeholder rather than an empty frame.
 */
export function CourseCover({
  src,
  title,
  className,
}: {
  src: string | null;
  title: string;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "relative aspect-video w-full overflow-hidden bg-gradient-to-br from-blue-100 via-sky-50 to-emerald-100 dark:from-blue-950 dark:via-slate-900 dark:to-emerald-950",
        className
      )}
    >
      <div className="absolute inset-0 flex items-center justify-center text-blue-700/50 dark:text-blue-200/40" aria-hidden="true">
        <BookOpen className="size-10" />
      </div>
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element -- short-lived signed cover URL on object storage
        <img
          src={src}
          alt={`Cover image for ${title}`}
          className="relative size-full object-cover"
          loading="lazy"
          onError={(event) => {
            event.currentTarget.style.display = "none";
          }}
        />
      ) : null}
    </div>
  );
}

/** Lesson resource icon (decorative; the resource label is rendered as text elsewhere). */
export function ResourceIcon({ resourceType, className }: { resourceType: string | null; className?: string }) {
  const kind = resourceKind(resourceType);
  const Icon = resourceType === "IMAGE" ? ImageIcon : kind === "live" ? Video : kind === "video" ? Clapperboard : kind === "document" ? FileText : File;
  return (
    <span
      className={cn(
        "inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200",
        className
      )}
      title={resourceLabel(resourceType)}
    >
      <Icon className="size-4" aria-hidden="true" />
    </span>
  );
}
