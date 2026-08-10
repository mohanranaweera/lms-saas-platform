import { CenteredAuthShell } from "@/components/layout/centered-auth-shell";

/**
 * Chrome for `/platform-admin/login` only — deliberately not the
 * `DashboardShell`/`PlatformAdminNav` chrome used by `/platform-admin/dashboard`
 * (that lives in the sibling nested route group,
 * `platform-admin/(dashboard)/layout.tsx`). A login page has no dashboard
 * shell. Shares `CenteredAuthShell` with the tenant-user login
 * (`app/(auth)/layout.tsx`) so both portals' sign-in screens look/behave
 * consistently without duplicating the layout.
 */
export default function PlatformAdminLoginLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <CenteredAuthShell homeLabel="LMS Platform — Platform Admin">{children}</CenteredAuthShell>;
}
