"use client";

import { TuteDispatchPage } from "@/components/tutes/tute-dispatch-page";
import { TENANT_ADMIN_TUTE_ROUTES } from "@/components/tutes/tute-routes";

/** TUTE-01 Tute Dispatch queue. Access is decided by the backend (TUTE_DISPATCH_VIEW). */
export default function TenantAdminTuteDispatchRoute() {
  return <TuteDispatchPage routes={TENANT_ADMIN_TUTE_ROUTES} />;
}
