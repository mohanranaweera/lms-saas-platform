import { TenantAdminMediaLibrary } from "./tenant-admin-media-library";

/**
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached entry -
 * with its canonical URL - for `/tenant-admin/media` and `/tenant-admin/media?folder=...`, so leaving
 * a folder (breadcrumb, "Back to Uploads") wrote `?folder=` straight back.
 * The open folder is still read client-side from the URL by `MediaLibrary`.
 */
export default async function TenantAdminMediaPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return <TenantAdminMediaLibrary />;
}
