"use client";

import { Suspense } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ListChecks, ShieldCheck, ShieldHalf, Users } from "lucide-react";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { LoadingState } from "@/components/states/loading-state";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP } from "@/lib/auth/capabilities";
import { UsersTab } from "./users-tab";
import { PermissionsTab, usePermissionEditsState } from "./permissions-tab";
import { ActivitiesTab } from "./activities-tab";

type StaffTab = "users" | "permissions" | "activities";
const TABS: readonly StaffTab[] = ["users", "permissions", "activities"];

function isStaffTab(value: string | null): value is StaffTab {
  return value !== null && (TABS as readonly string[]).includes(value);
}

/**
 * Staff Management (STAFF-MGMT-02, Klass parity): Users | Permissions |
 * Activities, the active tab in `?tab=`. Every in-page action is gated on my
 * effective capabilities (`GET /v1/me/permissions`) - UX only; each endpoint
 * re-checks its capability server-side and a real 403 renders the
 * permission-denied state.
 */
export default function StaffPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading staff…" />}>
      <StaffPageContent />
    </Suspense>
  );
}

function StaffPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { has, loaded } = useCapabilities({ fetchForAllRoles: true });
  const { edits, setEdits } = usePermissionEditsState();

  const canManage = has(CAP.STAFF_MANAGE);
  const canAssignRoles = has(CAP.STAFF_ASSIGN_ROLES);
  const canEditPermissions = has(CAP.STAFF_PERMISSIONS_EDIT);
  // Hidden only once my permissions are known to lack it; a direct visit still
  // makes the real request and renders the server's 403.
  const showActivities = !loaded || has(CAP.STAFF_ACTIVITY_VIEW);

  const tabParam = searchParams.get("tab");
  const activeTab: StaffTab = isStaffTab(tabParam) ? tabParam : "users";

  function handleTabChange(next: string) {
    const search = new URLSearchParams(searchParams);
    if (next === "users") search.delete("tab");
    else search.set("tab", next);
    const qs = search.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  const items: TabItem[] = [
    {
      value: "users",
      label: "Users",
      icon: <Users className="size-4" aria-hidden="true" />,
      content: <UsersTab canManage={canManage} canAssignRoles={canAssignRoles} />,
    },
    {
      value: "permissions",
      label: "Permissions",
      icon: <ShieldHalf className="size-4" aria-hidden="true" />,
      content: <PermissionsTab canEdit={canEditPermissions} edits={edits} setEdits={setEdits} />,
    },
  ];
  if (showActivities || activeTab === "activities") {
    items.push({
      value: "activities",
      label: "Activities",
      icon: <ListChecks className="size-4" aria-hidden="true" />,
      content: <ActivitiesTab />,
    });
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-3">
        <span
          className="inline-flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground"
          aria-hidden="true"
        >
          <ShieldCheck className="size-5" />
        </span>
        <h1 className="text-xl font-semibold text-foreground">Staff</h1>
      </div>
      <Tabs
        aria-label="Staff sections"
        idPrefix="staff-"
        items={items}
        value={activeTab}
        onValueChange={handleTabChange}
        listClassName="uppercase tracking-wide"
      />
    </div>
  );
}
