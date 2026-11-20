import { StaffWorkspace } from "./staff-workspace";

/**
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached entry -
 * with its canonical URL - for `/tenant-admin/staff` and `?tab=...`, so
 * switching tabs (router.replace) wrote the previous `?tab=` straight back.
 * The tab is still read client-side from the URL by `StaffWorkspace`.
 */
export default async function StaffPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return <StaffWorkspace />;
}
