import {
  BookOpen,
  CalendarCheck,
  ClipboardCheck,
  Eye,
  FileText,
  Info,
  LifeBuoy,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { STAFF_ROLE_OPTIONS, type StaffRoleCode } from "@/lib/validation/staff";

const ROLE_ICONS: Record<StaffRoleCode, LucideIcon> = {
  FINANCE_STAFF: Wallet,
  COURSE_COORDINATOR: BookOpen,
  STUDENT_SUPPORT: LifeBuoy,
  CONTENT_MANAGER: FileText,
  EXAM_MANAGER: ClipboardCheck,
  ATTENDANCE_OPERATOR: CalendarCheck,
  READ_ONLY_AUDITOR: Eye,
};

const ROLE_LABELS: Record<StaffRoleCode, string> = Object.fromEntries(
  STAFF_ROLE_OPTIONS.map((option) => [option.value, option.label])
) as Record<StaffRoleCode, string>;

const KNOWN_ROLE_CODES = new Set<string>(STAFF_ROLE_OPTIONS.map((option) => option.value));

function isKnownRoleCode(value: string): value is StaffRoleCode {
  return KNOWN_ROLE_CODES.has(value);
}

/**
 * Never color alone (`.claude/rules/ui-ux.md` §4) — every role pairs an icon
 * with its human-readable label. A `roleCode` this component doesn't
 * recognize (e.g. a role this frontend build predates) still renders the raw
 * code rather than throwing — this is read-only display, never a source of
 * authorization truth.
 */
export function RoleBadge({ roleCode }: { roleCode: string }) {
  const known = isKnownRoleCode(roleCode);
  const Icon = known ? ROLE_ICONS[roleCode] : Info;
  const label = known ? ROLE_LABELS[roleCode] : roleCode;

  return (
    <span className="inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
      <Icon className="size-3.5" aria-hidden="true" />
      {label}
    </span>
  );
}
