"use client";

import { ClassSessionCreateForm } from "@/components/live-classes/class-session-create-form";

import { useT } from "@/i18n/i18n-provider";
/** Teacher "Schedule Live Class" route (Wave 4 plan §5). See `ClassSessionCreateForm`'s own doc comment for behavior. */
export default function NewLiveClassPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">{t("teacher.page.liveClassesNew.scheduleALiveClass")}</h1>
        <p className="text-sm text-muted-foreground">
          {t("teacher.page.liveClassesNew.setUpAZoom")}
        </p>
      </div>
      <div className="w-full max-w-xl">
        <ClassSessionCreateForm />
      </div>
    </div>
  );
}
