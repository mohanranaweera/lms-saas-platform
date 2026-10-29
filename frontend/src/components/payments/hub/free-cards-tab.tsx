"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewFreeCards, canViewStudentPayments } from "@/lib/auth/permissions";
import { FreeCardsList } from "./free-cards-list";
import { StudentPaymentsWorkspaceView } from "./student-payments-workspace";

type FreeCardsView = "student" | "all";

/**
 * PAY-HUB-01 "Free Cards" tab: the student workspace (was "Student
 * Payments": record a payment, issue / revoke a free card) and the list of
 * every free card, as two views of one tab. The view lives in `?view=` so a
 * link can open either directly. UX only - the backend enforces
 * `FREE_CARDS` / `PAYMENTS_SLIPS` on every call.
 */
export function FreeCardsTab() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { session } = useAuth();
  const role = session?.role ?? null;
  const showWorkspace = canViewStudentPayments(role);
  const showList = canViewFreeCards(role);
  const requested = searchParams.get("view");
  const view: FreeCardsView =
    !showWorkspace || (requested === "all" && showList) ? "all" : "student";

  function selectView(next: FreeCardsView) {
    const search = new URLSearchParams(searchParams);
    search.set("view", next);
    if (next === "all") {
      search.delete("studentId");
      search.delete("courseId");
    }
    router.replace(`${pathname}?${search.toString()}`, { scroll: false });
  }

  return (
    <div className="flex flex-col gap-4">
      {showWorkspace && showList ? (
        <div role="group" aria-label="Free cards view" className="inline-flex w-fit rounded-lg border border-border p-0.5">
          <Button
            type="button"
            size="sm"
            variant={view === "student" ? "secondary" : "ghost"}
            aria-pressed={view === "student"}
            onClick={() => selectView("student")}
          >
            Student payments
          </Button>
          <Button
            type="button"
            size="sm"
            variant={view === "all" ? "secondary" : "ghost"}
            aria-pressed={view === "all"}
            onClick={() => selectView("all")}
          >
            All free cards
          </Button>
        </div>
      ) : null}
      {view === "student" ? <StudentPaymentsWorkspaceView /> : <FreeCardsList />}
    </div>
  );
}
