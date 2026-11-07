"use client";

import { useState } from "react";
import {
  CalendarPlus,
  Cake,
  Camera,
  Download,
  GraduationCap,
  Home,
  ListTree,
  Mail,
  MapPin,
  Pencil,
  Phone,
  Printer,
  School,
  Users,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { StudentAvatar } from "@/components/students/student-avatar";
import { ProfilePhotoEditor } from "@/components/students/profile-photo-editor";
import { isApiClientError } from "@/lib/api/error";
import { saveBlobAs, useStudentQrPng, type StudentDetail } from "@/lib/api/student-management";
import { formatZonedDateTime } from "@/lib/datetime";
import { InfoItem, guardianSummary, locationSummary, useStudentPhotoUrl } from "../student-display";

/**
 * Full-profile identity card (spec §1 sidebar) plus the student's QR. The QR
 * PNG is fetched with the bearer token and shown through an object URL — no
 * token or stable public URL is involved. The QR only identifies the student
 * to staff; it never logs anyone in.
 */
export function IdentitySidebar({
  detail,
  timeZone,
  canManage,
  onEdit,
}: {
  detail: StudentDetail;
  timeZone: string;
  canManage: boolean;
  onEdit: () => void;
}) {
  const photoUrl = useStudentPhotoUrl(detail, detail.id);
  const [photoOpen, setPhotoOpen] = useState(false);

  return (
    <aside className="flex flex-col gap-4" aria-label="Student identity" data-testid="student-identity">
      <section className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="flex items-center gap-3">
          <StudentAvatar name={detail.fullName} photoUrl={photoUrl} size="lg" />
          <div className="min-w-0">
            <h2 className="break-words text-base font-semibold text-foreground" data-testid="profile-student-name">
              {detail.fullName}
            </h2>
            <p className="text-sm text-muted-foreground">
              Index Number -{" "}
              <span className="font-mono tabular-nums" data-testid="profile-student-index">
                {detail.indexNumber ?? "—"}
              </span>
            </p>
            {detail.pending ? (
              <p className="text-xs font-medium text-amber-700 dark:text-amber-400">Pending approval</p>
            ) : null}
          </div>
        </div>
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="outline" onClick={onEdit}>
              <Pencil aria-hidden="true" />
              Edit
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              aria-expanded={photoOpen}
              onClick={() => setPhotoOpen((value) => !value)}
            >
              <Camera aria-hidden="true" />
              Change photo
            </Button>
          </div>
        ) : null}
        {canManage && photoOpen ? (
          <ProfilePhotoEditor owner={{ kind: "staff", studentId: detail.id }} name={detail.fullName} />
        ) : null}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-1">
          <InfoItem icon={<Users />} label="First Name" value={detail.firstName} />
          <InfoItem icon={<Users />} label="Last Name" value={detail.lastName} />
          <InfoItem icon={<Phone />} label="Mobile" value={detail.mobile} />
          <InfoItem icon={<Home />} label="Home" value={detail.fixedPhone} />
          <InfoItem icon={<Mail />} label="Email" value={<span className="break-all">{detail.email}</span>} />
          <InfoItem icon={<MapPin />} label="Location" value={locationSummary(detail)} />
          <InfoItem icon={<Cake />} label="Birthday" value={detail.dateOfBirth} />
          <InfoItem icon={<School />} label="School" value={detail.school} />
          <InfoItem icon={<GraduationCap />} label="A/L Year" value={detail.alYear !== null ? String(detail.alYear) : null} />
          <InfoItem icon={<ListTree />} label="A/L Stream" value={detail.stream} />
          <InfoItem icon={<Users />} label="Guardian" value={guardianSummary(detail)} testId="profile-guardian" />
          <InfoItem
            icon={<CalendarPlus />}
            label="Registered On"
            value={detail.registeredAt ? formatZonedDateTime(detail.registeredAt, timeZone) : null}
          />
        </div>
      </section>

      <StudentQrCard detail={detail} />
    </aside>
  );
}

function StudentQrCard({ detail }: { detail: StudentDetail }) {
  const query = useStudentQrPng(detail.id);
  const objectUrl = query.data?.dataUrl ?? null;

  const fileBase = `student-${detail.indexNumber ?? detail.id.slice(0, 8)}-qr`;

  function print() {
    if (!objectUrl) return;
    const frame = document.createElement("iframe");
    frame.setAttribute("aria-hidden", "true");
    frame.style.position = "fixed";
    frame.style.width = "0";
    frame.style.height = "0";
    frame.style.border = "0";
    document.body.appendChild(frame);
    const doc = frame.contentDocument;
    if (!doc) {
      frame.remove();
      return;
    }
    doc.open();
    doc.write("<!doctype html><title>Student QR</title><body style=\"font-family:sans-serif;text-align:center\"></body>");
    doc.close();
    const img = doc.createElement("img");
    img.style.width = "60mm";
    img.alt = "Student QR code";
    const name = doc.createElement("p");
    name.textContent = detail.fullName;
    const index = doc.createElement("p");
    index.textContent = detail.indexNumber ?? "";
    img.onload = () => {
      frame.contentWindow?.focus();
      frame.contentWindow?.print();
      setTimeout(() => frame.remove(), 1000);
    };
    doc.body.append(img, name, index);
    img.src = objectUrl;
  }

  return (
    <section
      className="flex flex-col items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm"
      aria-labelledby="student-qr-heading"
      data-testid="student-qr-card"
    >
      <h2 id="student-qr-heading" className="self-start text-sm font-semibold text-foreground">
        Student QR
      </h2>
      {query.status === "pending" || (query.status === "success" && !objectUrl) ? (
        <Skeleton className="size-44" />
      ) : query.status === "error" ? (
        <div role="alert" className="flex flex-col items-center gap-2 text-center text-sm">
          <p className="text-destructive">
            {isApiClientError(query.error) && query.error.status === 403
              ? "You don't have permission to view this QR code."
              : "The QR code couldn't be loaded."}
          </p>
          <Button type="button" size="sm" variant="outline" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : (
        // eslint-disable-next-line @next/next/no-img-element -- in-memory data URL of an authorized download
        <img
          src={objectUrl!}
          alt={`QR code for ${detail.fullName}`}
          className="size-44 rounded-md bg-white p-2"
          data-testid="student-qr-image"
        />
      )}
      <p className="text-center text-xs text-muted-foreground">Identifies the student to staff. It can&apos;t be used to sign in.</p>
      <div className="flex flex-wrap justify-center gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={!query.data}
          onClick={() => query.data && saveBlobAs(query.data.blob, `${fileBase}.png`)}
        >
          <Download aria-hidden="true" />
          Download PNG
        </Button>
        <Button type="button" size="sm" variant="outline" disabled={!objectUrl} onClick={print}>
          <Printer aria-hidden="true" />
          Print
        </Button>
      </div>
    </section>
  );
}
