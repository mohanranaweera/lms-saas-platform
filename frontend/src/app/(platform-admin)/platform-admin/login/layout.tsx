import Link from "next/link";

/**
 * Chrome for `/platform-admin/login` only — deliberately not the
 * `DashboardShell`/`PlatformAdminNav` chrome used by `/platform-admin/dashboard`
 * (that lives in the sibling nested route group,
 * `platform-admin/(dashboard)/layout.tsx`). A login page has no dashboard
 * shell. Mirrors `app/(auth)/layout.tsx`'s centered-card pattern for the
 * tenant-user login so both portals' sign-in screens look/behave consistently.
 */
export default function PlatformAdminLoginLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/30 px-4 py-12">
      <Link href="/" className="text-lg font-semibold text-foreground">
        LMS Platform — Platform Admin
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </div>
  );
}
