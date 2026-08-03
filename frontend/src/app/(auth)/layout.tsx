import Link from "next/link";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/30 px-4 py-12">
      <Link href="/" className="text-lg font-semibold text-foreground">
        LMS Platform
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </div>
  );
}
