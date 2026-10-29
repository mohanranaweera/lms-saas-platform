import { PublicChrome } from "@/components/public/public-chrome";

/**
 * Public route group (`/`, `/courses`, `/teachers`, …). The tenant-themed
 * header/footer and theme scope live in `PublicChrome`; pages render body
 * content only (no page renders its own header/footer).
 */
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return <PublicChrome>{children}</PublicChrome>;
}
