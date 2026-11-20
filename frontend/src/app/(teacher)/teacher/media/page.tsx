import { TeacherMediaLibrary } from "./teacher-media-library";

/**
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached entry -
 * with its canonical URL - for `/teacher/media` and `/teacher/media?folder=...`, so leaving
 * a folder (breadcrumb, "Back to Uploads") wrote `?folder=` straight back.
 * The open folder is still read client-side from the URL by `MediaLibrary`.
 */
export default async function TeacherMediaPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return <TeacherMediaLibrary />;
}
