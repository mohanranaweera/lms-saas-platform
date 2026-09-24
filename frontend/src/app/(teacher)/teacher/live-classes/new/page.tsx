"use client";

import { ClassSessionCreateForm } from "@/components/live-classes/class-session-create-form";

/** Teacher "Schedule Live Class" route (Wave 4 plan §5). See `ClassSessionCreateForm`'s own doc comment for behavior. */
export default function NewLiveClassPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Schedule a live class</h1>
        <p className="text-sm text-muted-foreground">
          Set up a Zoom-backed live class session for one of your courses.
        </p>
      </div>
      <div className="w-full max-w-xl">
        <ClassSessionCreateForm />
      </div>
    </div>
  );
}
