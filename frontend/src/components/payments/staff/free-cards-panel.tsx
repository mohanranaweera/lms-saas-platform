"use client";

import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { formatYearMonth, useFreeCards } from "@/lib/api/free-cards";
import { formatDateTime } from "@/lib/format";
import { FreeCardStatusBadge } from "./staff-status-badges";
import { RevokeFreeCardDialog } from "./revoke-free-card-dialog";

/** Free cards of the selected student + course, with Revoke for `canRevoke` (UX; backend enforces). */
export function FreeCardsPanel({
  studentId,
  courseId,
  courseTitle,
  canRevoke,
  onAnnounce,
}: {
  studentId: string;
  courseId: string;
  courseTitle: string;
  canRevoke: boolean;
  onAnnounce: (message: string) => void;
}) {
  const query = useFreeCards({ studentId, courseId, size: 50 });
  return (
    <section className="flex flex-col rounded-lg border border-border bg-card" aria-labelledby="free-cards-heading">
      <h2 id="free-cards-heading" className="border-b border-border px-4 py-3 text-base font-semibold text-foreground">
        Free cards
      </h2>
      <div className="p-4">
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading free cards…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.content.length === 0}
          emptyState={{
            title: "No free cards",
            description: `This student has no free card for ${courseTitle}.`,
          }}
        >
          {(data) => (
            <ul className="flex flex-col divide-y divide-border" aria-label="Free cards" data-testid="free-card-list">
              {data.content.map((card) => (
                <li key={card.id} className="flex flex-col gap-1 py-2" data-testid="free-card-row">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-medium text-foreground">{card.coveredMonths.map(formatYearMonth).join(", ")}</span>
                    <FreeCardStatusBadge status={card.status} />
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Valid {card.validFrom} – {card.validTo} · Full · Issued by {card.issuedByName ?? card.issuedBy} on{" "}
                    {formatDateTime(card.issuedAt)}
                  </p>
                  <p className="text-xs text-foreground">Reason: {card.reason}</p>
                  {card.status === "REVOKED" ? (
                    <p className="text-xs text-muted-foreground">
                      Revoked{card.revokedAt ? ` ${formatDateTime(card.revokedAt)}` : ""}
                      {card.revokedByName ? ` by ${card.revokedByName}` : ""}
                      {card.revokedReason ? ` – ${card.revokedReason}` : ""}
                    </p>
                  ) : canRevoke ? (
                    <div>
                      <RevokeFreeCardDialog card={card} onRevoked={onAnnounce} />
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      </div>
    </section>
  );
}
