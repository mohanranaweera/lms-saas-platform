import Link from "next/link";
import { PublicHeaderNav } from "@/components/layout/public-header-nav";

export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="flex flex-col gap-3 border-b border-border px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <Link href="/" className="text-lg font-semibold text-foreground">
          LMS Platform
        </Link>
        <PublicHeaderNav />
      </header>
      <main className="flex flex-1 flex-col">{children}</main>
      <footer className="border-t border-border px-4 py-4 text-center text-sm text-muted-foreground sm:px-6">
        &copy; {new Date().getFullYear()} LMS Platform. All rights reserved.
      </footer>
    </div>
  );
}
