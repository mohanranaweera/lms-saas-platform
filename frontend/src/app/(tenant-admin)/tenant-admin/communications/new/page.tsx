import { NewBroadcastPageClient } from "./page-client";

/**
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached entry -
 * with its canonical URL - for `/tenant-admin/communications/new` and its query-string variants, so a
 * router.replace/push that changes the query wrote the previous one straight
 * back. The page still reads its state client-side from the URL.
 */
export default async function NewBroadcastPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return <NewBroadcastPageClient />;
}
