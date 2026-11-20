/**
 * Stable per-course chip colour: the course id is hashed (FNV-1a) into a
 * fixed palette. Every background below has at least 4.5:1 contrast with
 * white text (WCAG AA for small text). Colour is never the only signal —
 * chips always carry the time and course name, and status is shown as text.
 */
const PALETTE = [
  "#1D4ED8", // blue-700
  "#047857", // emerald-700
  "#7E22CE", // purple-700
  "#BE185D", // pink-700
  "#0F766E", // teal-700
  "#C2410C", // orange-700
  "#4338CA", // indigo-700
  "#0369A1", // sky-700
  "#A21CAF", // fuchsia-700
  "#15803D", // green-700
  "#374151", // gray-700
  "#9F1239", // rose-800
] as const;

export function courseColor(courseId: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < courseId.length; i += 1) {
    hash ^= courseId.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return PALETTE[(hash >>> 0) % PALETTE.length];
}
