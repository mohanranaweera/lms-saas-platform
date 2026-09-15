import type { ReactNode } from "react";

/**
 * Shared "Page Header" primitive (`docs/ui-ux/component-library-spec.md`
 * §3.4): H1 title → optional description → optional primary action
 * `Button`(s), right-aligned on desktop / stacked below the title on mobile.
 * First implementation in this codebase — first consumer: Platform Admin's
 * Tenant/Payments/Audit Log screens (MVP-020).
 *
 * `showTenantContext`/`tenantContext` render the persistent tenant-context
 * banner required by `.claude/rules/ui-ux.md` §1 for a Platform Admin
 * single-tenant drill-down: a strip naming the tenant, with an optional
 * caller-supplied status indicator slot (`tenantContext.statusBadge`) rather
 * than this component importing a specific feature's status-badge/enum
 * directly — `components/ui/*` is meant to be the dependency-graph floor
 * (`.claude/rules/frontend.md`: "Shared, generic UI ... lives in
 * `components/ui/`"), so it must stay unaware of any one feature's
 * domain-specific status vocabulary (e.g. Platform Admin's `TenantStatus`);
 * callers pass their own status-badge element instead. This banner has **no
 * dismiss/close affordance anywhere in its markup** — enforced structurally
 * here (there is no button/icon that closes it), not just by convention,
 * since a dismissible banner would defeat the "persistent, non-dismissible"
 * requirement regardless of how a caller used this component.
 */
export interface PageHeaderTenantContext {
  id: string;
  name: string;
  /** Caller-supplied status indicator (e.g. `<StatusBadge status={tenant.status} />`), rendered as-is next to the tenant name. Omit if the caller has no status to show. */
  statusBadge?: ReactNode;
}

export interface PageHeaderProps {
  title: string;
  description?: string;
  actions?: ReactNode;
  showTenantContext?: boolean;
  tenantContext?: PageHeaderTenantContext;
}

export function PageHeader({
  title,
  description,
  actions,
  showTenantContext = false,
  tenantContext,
}: PageHeaderProps) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">{title}</h1>
          {description ? (
            <p className="text-sm text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {actions ? (
          <div className="flex flex-wrap items-center gap-2">{actions}</div>
        ) : null}
      </div>

      {showTenantContext && tenantContext ? (
        <div
          role="note"
          aria-label={`Viewing tenant: ${tenantContext.name}`}
          className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-sm"
        >
          <span className="font-medium text-foreground">Viewing tenant:</span>
          <span className="text-foreground">{tenantContext.name}</span>
          {tenantContext.statusBadge}
        </div>
      ) : null}
    </div>
  );
}
