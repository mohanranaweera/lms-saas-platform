/**
 * Display-only helpers for the student course screens: dates in the course
 * (tenant) timezone and lesson resource labels. Nothing here decides access.
 */

function safeTimeZone(timeZone: string | null | undefined): string | undefined {
  if (!timeZone) return undefined;
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone });
    return timeZone;
  } catch {
    return undefined;
  }
}

/** "10 Oct 2026, 07:00 am" in the course timezone. */
export function formatLessonDateTime(iso: string, timeZone: string | null | undefined): string {
  const formatter = new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
    timeZone: safeTimeZone(timeZone),
  });
  return formatter.format(new Date(iso)).replace(/\b(AM|PM)\b/g, (m) => m.toLowerCase());
}

/** "7:00 AM" in the course timezone. */
export function formatTimeOfDay(iso: string, timeZone: string | null | undefined): string {
  return new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
    timeZone: safeTimeZone(timeZone),
  }).format(new Date(iso));
}

/** "SATURDAY, 10 OCTOBER" grouping label + a stable yyyy-mm-dd key, in the course timezone. */
export function dayGroup(iso: string, timeZone: string | null | undefined): { key: string; label: string } {
  const tz = safeTimeZone(timeZone);
  const date = new Date(iso);
  const key = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone: tz,
  }).format(date);
  const label = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    timeZone: tz,
  }).format(date);
  return { key, label };
}

const RESOURCE_LABELS: Record<string, string> = {
  VIMEO_VIDEO: "Vimeo video",
  SECURE_VIDEO: "Player Video",
  DOCUMENT: "Document",
  IMAGE: "Image",
  ZOOM_LINK: "Zoom",
  ZOOM_MEETING: "Zoom",
  ZOOM_WEBINAR: "Zoom webinar",
  ZOOM_RECORDING: "Zoom recording",
  VIDEO_LINK: "Video link",
  GOOGLE_DRIVE_LINK: "Google Drive",
  MS_TEAMS_LINK: "Microsoft Teams",
  GOOGLE_MEET: "Google Meet",
};

export function resourceLabel(resourceType: string | null | undefined): string {
  if (!resourceType) return "Lesson";
  return RESOURCE_LABELS[resourceType] ?? "Lesson";
}

export type ResourceKind = "live" | "video" | "document" | "other";

export function resourceKind(resourceType: string | null | undefined): ResourceKind {
  switch (resourceType) {
    case "ZOOM_LINK":
    case "ZOOM_MEETING":
    case "ZOOM_WEBINAR":
    case "MS_TEAMS_LINK":
    case "GOOGLE_MEET":
      return "live";
    case "VIMEO_VIDEO":
    case "SECURE_VIDEO":
    case "VIDEO_LINK":
    case "ZOOM_RECORDING":
      return "video";
    case "DOCUMENT":
    case "IMAGE":
    case "GOOGLE_DRIVE_LINK":
      return "document";
    default:
      return "other";
  }
}
