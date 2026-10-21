"use client";

import type { ReactNode } from "react";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import type { DeviceView } from "@/lib/api/devices";
import { formatDateTime } from "@/lib/format";
import { DeviceStatusBadge } from "./device-status-badge";

interface DeviceListProps {
  devices: DeviceView[];
  caption: string;
  /** Show the last-seen IP (staff review only). */
  showIp?: boolean;
  /** Per-row action (remove/reset) - the caller decides who sees it; the backend decides who may use it. */
  action?: (device: DeviceView) => ReactNode;
}

function removalText(device: DeviceView): string {
  if (!device.revokedAt) {
    return "—";
  }
  const who =
    device.revocationSource === "ADMIN"
      ? "by the institute"
      : device.revocationSource === "STUDENT"
        ? "by the account holder"
        : "";
  const reason = device.revokeReason ? ` — ${device.revokeReason}` : "";
  return `${formatDateTime(device.revokedAt)} ${who}${reason}`.trim();
}

/**
 * Shared device table (Wave 10) for My Devices (Student) and the Student
 * detail Devices tab (staff). Card view below `md` via `DataTable`.
 */
export function DeviceList({ devices, caption, showIp = false, action }: DeviceListProps) {
  const columns: DataTableColumn<DeviceView>[] = [
    { key: "label", header: "Device", cell: (row) => row.label, hideOnCard: true },
    { key: "status", header: "Status", cell: (row) => <DeviceStatusBadge device={row} />, hideOnCard: true },
    { key: "firstSeenAt", header: "Added", cell: (row) => formatDateTime(row.firstSeenAt) },
    { key: "lastSeenAt", header: "Last used", cell: (row) => formatDateTime(row.lastSeenAt) },
    ...(showIp
      ? [{ key: "lastIp", header: "Last IP", cell: (row: DeviceView) => row.lastIp ?? "—" }]
      : []),
    { key: "removed", header: "Removed", cell: (row) => removalText(row) },
    {
      key: "slot",
      header: "Slot free again",
      cell: (row) => (row.slotReusableAt ? formatDateTime(row.slotReusableAt) : "—"),
    },
    ...(action
      ? [{ key: "actions", header: "Actions", cell: (row: DeviceView) => action(row), hideOnCard: true }]
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
