import type { TenantStatus } from "./status-badge";

/**
 * Clearly-synthetic local sample data for the Tenant List scaffold. There is no
 * backend endpoint for this list yet (`GET /api/v1/platform-admin/tenants` is
 * designed but unbuilt — see module plan §20/§21 item 13) — do not replace this
 * with a fetch until that endpoint and Platform Admin authentication both exist.
 */
export interface MockTenant {
  id: string;
  name: string;
  subdomain: string;
  status: TenantStatus;
  requestedPlan: string;
  submittedAt: string; // ISO date
}

export const MOCK_TENANTS: MockTenant[] = [
  {
    id: "sample-1",
    name: "Example Institute A",
    subdomain: "example-institute-a",
    status: "pending_approval",
    requestedPlan: "Growth (up to 500 students)",
    submittedAt: "2026-08-01",
  },
  {
    id: "sample-2",
    name: "Example Institute B",
    subdomain: "example-institute-b",
    status: "pending_approval",
    requestedPlan: "Starter",
    submittedAt: "2026-08-05",
  },
  {
    id: "sample-3",
    name: "Example Tutoring Co",
    subdomain: "example-tutoring-co",
    status: "trial",
    requestedPlan: "Growth (up to 500 students)",
    submittedAt: "2026-07-20",
  },
  {
    id: "sample-4",
    name: "Example Academy",
    subdomain: "example-academy",
    status: "active",
    requestedPlan: "Enterprise / custom",
    submittedAt: "2026-06-15",
  },
  {
    id: "sample-5",
    name: "Example Learning Centre",
    subdomain: "example-learning-centre",
    status: "active",
    requestedPlan: "Growth (up to 500 students)",
    submittedAt: "2026-05-30",
  },
  {
    id: "sample-6",
    name: "Example Coaching Institute",
    subdomain: "example-coaching-institute",
    status: "suspended",
    requestedPlan: "Starter",
    submittedAt: "2026-04-11",
  },
];
