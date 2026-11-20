"use client";

import { MediaDialog } from "@/components/media/media-dialog";
import type { Teacher } from "@/lib/api/teachers";
import { TeacherProfileForm } from "./[teacherId]/teacher-profile-form";

export function TeacherEditSheet({
  teacher,
  open,
  onOpenChange,
  onSaved,
}: {
  teacher: Teacher | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved: (teacher: Teacher) => void;
}) {
  if (!teacher) return null;

  return (
    <MediaDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Edit ${teacher.name}`}
      description="Update this teacher's profile and profile image."
      className="sm:max-w-2xl"
      footer={null}
    >
      <TeacherProfileForm
        teacher={teacher}
        onCancel={() => onOpenChange(false)}
        onSaved={(saved) => {
          onSaved(saved);
          onOpenChange(false);
        }}
      />
    </MediaDialog>
  );
}
