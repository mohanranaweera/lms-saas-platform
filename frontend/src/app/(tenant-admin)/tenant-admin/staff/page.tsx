"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ListChecks, ShieldCheck, ShieldHalf, Users } from "lucide-react";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { LoadingState } from "@/components/states/loading-state";
import { useCapabilities } from "@/lib/api/my-permissions";
import { usePermissionMatrix } from "@/lib/api/staff";
import { CAP } from "@/lib/auth/capabilities";
import { UsersTab } from "./users-tab";
import { PermissionsTab, pendingChanges, usePermissionEditsState } from "./permissions-tab";
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

/**
 * While `active`, warns before the page is closed/reloaded (`beforeunload`)
 * and intercepts in-app link clicks (sidebar, breadcrumbs, any `<a>` leaving
 * this route) to ask for confirmation first. Returns the pending destination
 * and the confirm/cancel handlers for an accessible dialog.
 */
function useUnsavedChangesGuard(active: boolean) {
  const router = useRouter();
  const pathname = usePathname();
  const [pendingHref, setPendingHref] = useState<string | null>(null);

  useEffect(() => {
    if (!active) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    // Capture phase on document runs before React's root listener (and so before next/link).
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0) return;
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = (event.target as Element | null)?.closest?.("a[href]");
      if (!(anchor instanceof HTMLAnchorElement)) return;
      if (anchor.target && anchor.target !== "_self") return;
      if (anchor.hasAttribute("download")) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.origin !== window.location.origin || url.pathname === pathname) return;
      event.preventDefault();
      event.stopPropagation();
      setPendingHref(`${url.pathname}${url.search}${url.hash}`);
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    document.addEventListener("click", onClick, true);
    return () => {
      window.removeEventListener("beforeunload", onBeforeUnload);
      document.removeEventListener("click", onClick, true);
    };
  }, [active, pathname]);

  return {
    pendingHref: active ? pendingHref : null,
    stay: () => setPendingHref(null),
    leave: () => {
      const href = pendingHref;
      setPendingHref(null);
      if (href) router.push(href);
    },
  };
}

function StaffPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { has, loaded } = useCapabilities({ fetchForAllRoles: true });
  const { edits, setEdits } = usePermissionEditsState();
  // Cache observer only: the Permissions tab owns the fetch.
  const matrixCache = usePermissionMatrix({ enabled: false });

  const canManage = has(CAP.STAFF_MANAGE);
  const canAssignRoles = has(CAP.STAFF_ASSIGN_ROLES);
  const canEditPermissions = has(CAP.STAFF_PERMISSIONS_EDIT);
  // Hidden only once my permissions are known to lack it; a direct visit still
  // makes the real request and renders the server's 403.
  const showActivities = !loaded || has(CAP.STAFF_ACTIVITY_VIEW);

  const unsavedCount = useMemo(
    () => (canEditPermissions ? pendingChanges(matrixCache.data, edits).length : 0),
    [canEditPermissions, matrixCache.data, edits]
  );
  const guard = useUnsavedChangesGuard(unsavedCount > 0);

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
      content: <UsersTab canManage={canManage} canAssignRoles={canAssignRoles} capabilitiesLoaded={loaded} />,
    },
    {
      value: "permissions",
      label: "Permissions",
      icon: <ShieldHalf className="size-4" aria-hidden="true" />,
      content: (
        <PermissionsTab
          canEdit={canEditPermissions}
          capabilitiesLoaded={loaded}
          edits={edits}
          setEdits={setEdits}
        />
      ),
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

      <AlertDialog open={guard.pendingHref !== null} onOpenChange={(open) => (open ? undefined : guard.stay())}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Leave without saving?</AlertDialogTitle>
            <AlertDialogDescription>
              You have {unsavedCount} unsaved permission {unsavedCount === 1 ? "change" : "changes"}. They will be lost
              if you leave this page.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <Button type="button" variant="outline" onClick={guard.stay}>
              Stay on page
            </Button>
            <Button type="button" variant="destructive" onClick={guard.leave}>
              Leave
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
