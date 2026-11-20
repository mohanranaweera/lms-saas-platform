"use client";

import type { ReactNode } from "react";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import type { DeviceView } from "@/lib/api/devices";
import { formatDateTime } from "@/lib/format";
import { DeviceStatusBadge } from "./device-status-badge";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";

interface DeviceListProps {
  devices: DeviceView[];
  caption: string;
  /** Show the last-seen IP (staff review only). */
  showIp?: boolean;
  /** Per-row action (remove/reset) - the caller decides who sees it; the backend decides who may use it. */
  action?: (device: DeviceView) => ReactNode;
}

function removalText(device: DeviceView, t?: TranslateFn): string {
  if (!device.revokedAt) {
    return "—";
  }
  const when = formatDateTime(device.revokedAt);
  const reason = device.revokeReason ? ` — ${device.revokeReason}` : "";
  if (t) {
    const who =
      device.revocationSource === "ADMIN"
        ? t("devices.removedByInstitute", { when })
        : device.revocationSource === "STUDENT"
          ? t("devices.removedByHolder", { when })
          : when;
    return `${who}${reason}`;
  }
  const who =
    device.revocationSource === "ADMIN"
      ? "by the institute"
      : device.revocationSource === "STUDENT"
        ? "by the account holder"
        : "";
  return `${when} ${who}${reason}`.trim();
}

/**
 * Shared device table (Wave 10) for My Devices (Student) and the Student
 * detail Devices tab (staff). Card view below `md` via `DataTable`.
 */
export function DeviceList({ devices, caption, showIp = false, action }: DeviceListProps) {
  const t = useT();
  const columns: DataTableColumn<DeviceView>[] = [
    { key: "label", header: t("devices.col.device"), cell: (row) => row.label, hideOnCard: true },
    { key: "status", header: t("devices.col.status"), cell: (row) => <DeviceStatusBadge device={row} />, hideOnCard: true },
    { key: "firstSeenAt", header: t("devices.col.added"), cell: (row) => formatDateTime(row.firstSeenAt) },
    { key: "lastSeenAt", header: t("devices.col.lastUsed"), cell: (row) => formatDateTime(row.lastSeenAt) },
    ...(showIp
      ? [{ key: "lastIp", header: t("devices.col.lastIp"), cell: (row: DeviceView) => row.lastIp ?? "—" }]
      : []),
    { key: "removed", header: t("devices.col.removed"), cell: (row) => removalText(row, t) },
    {
      key: "slot",
      header: t("devices.col.slotFree"),
      cell: (row) => (row.slotReusableAt ? formatDateTime(row.slotReusableAt) : "—"),
    },
    ...(action
      ? [{ key: "actions", header: t("devices.col.actions"), cell: (row: DeviceView) => action(row), hideOnCard: true }]
      : []),
  ];

  return (
    <DataTable
      columns={columns}
      rows={devices}
      rowKey={(row) => row.id}
      caption={caption}
      cardHeading={(row) => row.label}
      cardHeadingAdornment={(row) => <DeviceStatusBadge device={row} />}
      cardFooter={action ? (row) => action(row) : undefined}
    />
  );
}
