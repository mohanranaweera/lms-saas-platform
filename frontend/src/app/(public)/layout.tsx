import Link from "next/link";

export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="flex items-center justify-between border-b border-border px-4 py-4 sm:px-6">
        <Link href="/" className="text-lg font-semibold text-foreground">
          LMS Platform
        </Link>
        <nav aria-label="Account" className="flex items-center gap-3">
          <Link
            href="/login"
            className="text-sm font-medium text-foreground hover:underline"
          >
            Sign in
          </Link>
          <Link
            href="/register"
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            Get started
          </Link>
        </nav>
      </header>
      <main className="flex flex-1 flex-col">{children}</main>
      <footer className="border-t border-border px-4 py-4 text-center text-sm text-muted-foreground sm:px-6">
        &copy; {new Date().getFullYear()} LMS Platform. All rights reserved.
      </footer>
    </div>
  );
}
