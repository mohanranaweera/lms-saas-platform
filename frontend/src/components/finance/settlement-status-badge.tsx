import { CheckCircle2, Clock } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { TeacherSettlementStatus } from "@/lib/api/finance";

/** Settlement status chip — icon + text, never color alone (spec 24 §8). */
export function SettlementStatusBadge({ status }: { status: TeacherSettlementStatus }) {
  if (status === "PAID") {
    return (
      <Badge variant="secondary">
        <CheckCircle2 aria-hidden="true" />
        Paid
      </Badge>
    );
  }
  return (
    <Badge variant="outline">
      <Clock aria-hidden="true" />
      Calculated — unpaid
    </Badge>
  );
}
