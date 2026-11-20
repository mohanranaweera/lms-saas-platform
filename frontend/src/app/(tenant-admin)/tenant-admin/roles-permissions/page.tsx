import { redirect } from "next/navigation";

/**
 * Roles & Permissions moved (STAFF-MGMT-02): role permissions are now the
 * tenant-configurable matrix under Staff → Permissions. This old route only
 * redirects there so existing bookmarks keep working; the destination makes
 * the real, server-authorized requests.
 */
export default function RolesAndPermissionsPage() {
  redirect("/tenant-admin/staff?tab=permissions");
}
