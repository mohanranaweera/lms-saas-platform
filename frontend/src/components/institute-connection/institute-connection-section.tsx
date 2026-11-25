"use client";

import { isApiClientError } from "@/lib/api/error";
import {
  useInstituteConnection,
  usePlatformInstituteConnection,
  type InstituteConnection,
} from "@/lib/api/institute-connection";
import type { UseQueryResult } from "@tanstack/react-query";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { InstituteConnectionCard } from "./institute-connection-card";

/**
 * INST-QR-01 data wrappers for the two admin placements. Loading, error and
 * "not configured" states live here so each placement stays one line.
 */

/** Tenant Admin dashboard: the signed-in institute's own QR. */
export function TenantInstituteConnectionSection() {
  const query = useInstituteConnection();
  return <Section query={query} />;
}

/** Platform Admin tenant details: the selected institute's QR, named explicitly. */
export function PlatformInstituteConnectionSection({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const query = usePlatformInstituteConnection(tenantId);
  return (
    <Section
      // Remount per tenant so no local share state crosses tenants.
      key={tenantId}
      query={query}
      expectedTenantId={tenantId}
      scopeNote={`Institute QR for ${tenantName}. Share it with this institute's students or admins.`}
    />
  );
}

function Section({
  query,
  expectedTenantId,
  scopeNote,
}: {
  query: UseQueryResult<InstituteConnection>;
  expectedTenantId?: string;
  scopeNote?: string;
}) {
  if (query.isPending) {
    return (
      <section aria-label="Institute connection" className="rounded-xl border border-border px-4 py-5 sm:px-6">
        <LoadingState label="Loading institute QR…" />
      </section>
    );
  }
  if (query.isError) {
    const error = query.error;
    if (isApiClientError(error) && error.code === "INSTITUTE_CONNECTION_NOT_CONFIGURED") {
      return (
        <section aria-label="Institute connection" className="rounded-xl border border-dashed border-border px-4 py-5 text-sm text-muted-foreground sm:px-6">
          <p className="font-medium text-foreground">Institute QR isn&apos;t set up yet</p>
          <p>The platform team hasn&apos;t configured connection links. Once they do, the QR appears here.</p>
        </section>
      );
    }
    if (isApiClientError(error) && (error.status === 403 || error.status === 401)) {
      // No permission: nothing to show (the backend refused; this only hides the card).
      return null;
    }
    return (
      <section aria-label="Institute connection" className="rounded-xl border border-border px-4 py-5 sm:px-6">
        <ErrorState
          message={isApiClientError(error) ? error.message : "Couldn't load the institute QR."}
          onRetry={() => query.refetch()}
        />
      </section>
    );
  }
  // Belt and braces: never render a QR that isn't the one asked for.
  if (expectedTenantId && query.data.tenantId !== expectedTenantId) return null;
  return <InstituteConnectionCard connection={query.data} scopeNote={scopeNote} />;
}
