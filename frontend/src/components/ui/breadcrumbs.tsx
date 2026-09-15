import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Shared "Breadcrumbs" primitive (`docs/ui-ux/component-library-spec.md`
 * §2.12): chevron-separated link chain, current/last page rendered as
 * non-interactive text with `aria-current="page"`. First implementation in
 * this codebase — first consumer: Platform Admin's tenant payment/audit-log
 * drill-down screens (MVP-020). Per `ui-ux.md` §1, breadcrumbs alone are not
 * sufficient to satisfy the persistent tenant-context-banner requirement —
 * pair with `PageHeader`'s `showTenantContext` on drill-down screens.
 */
export interface BreadcrumbItem {
  label: string;
  /** Omit on the current/last item — it renders as non-interactive text. */
  href?: string;
}

export function Breadcrumbs({ items }: { items: BreadcrumbItem[] }) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li key={`${item.label}-${index}`} className="flex items-center gap-1.5">
              {index > 0 ? (
                <ChevronRight className="size-3.5 shrink-0" aria-hidden="true" />
              ) : null}
              {item.href && !isLast ? (
                <Link href={item.href} className="hover:text-foreground hover:underline">
                  {item.label}
                </Link>
              ) : (
                <span
                  aria-current={isLast ? "page" : undefined}
                  className={cn(isLast && "font-medium text-foreground")}
                >
                  {item.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
