"use client";

import { LoadingState } from "@/components/states/loading-state";
import { useT } from "@/i18n/i18n-provider";

/** Localized loading fallbacks for student server pages (I18N-01). */
export function StudentCoursesLoading() {
  const t = useT();
  return <LoadingState label={t("student.courses.loading")} />;
}
