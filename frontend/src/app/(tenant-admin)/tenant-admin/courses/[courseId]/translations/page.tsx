"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourseLocalizations } from "@/lib/api/translations";
import type { CourseResponse } from "@/lib/api/courses";
import { useNamespaces, useT } from "@/i18n/i18n-provider";
import { LOCALES, localeAutonym, sameLanguage } from "@/i18n/locales";
import { CourseLocalizationCard } from "./course-localization-card";

/**
 * Tenant Admin course workspace -> Translations (I18N-01 §8). Per-locale
 * public title/description variants of ONE canonical course (same id, slug,
 * owner and authorization). Drafts are visible only here; the public site
 * serves a PUBLISHED variant for the visitor's resolved locale, else the
 * original. Editing and publishing are authorized server-side (COURSES_EDIT).
 */
export default function CourseTranslationsPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  useNamespaces(["i18nAdmin"]);

  return (
    <CourseWorkspaceShell courseId={courseId} basePath={`/tenant-admin/courses/${courseId}`} dashboardHref="/tenant-admin/dashboard">
      {(course) => <CourseTranslations course={course} />}
    </CourseWorkspaceShell>
  );
}

function CourseTranslations({ course }: { course: CourseResponse }) {
  const t = useT();
  const query = useCourseLocalizations(course.id);
  const originalLocale = query.data?.contentLocale ?? course.contentLocale ?? null;
  const targetLocales = LOCALES.filter((locale) => !originalLocale || !sameLanguage(locale.tag, originalLocale));

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="course-translations">
      <section aria-labelledby="course-original-heading" className="flex min-w-0 flex-col gap-2 rounded-lg border border-border p-4">
        <h2 id="course-original-heading" className="text-base font-medium text-foreground">
          {t("i18nAdmin.course.original")}
        </h2>
        <p className="text-xs text-muted-foreground" data-testid="course-original-locale">
          {originalLocale
            ? t("i18nAdmin.course.originalLanguage", { language: `${localeAutonym(originalLocale)} (${originalLocale})` })
            : t("i18nAdmin.course.languageNotDeclared")}
        </p>
        <p className="font-medium break-words text-foreground" lang={originalLocale ?? undefined}>
          {course.name}
        </p>
        {course.description ? (
          <p className="text-sm break-words whitespace-pre-wrap text-muted-foreground" lang={originalLocale ?? undefined}>
            {course.description}
          </p>
        ) : null}
      </section>

      <QueryStateBoundary
        query={query}
        loadingLabel={t("i18nAdmin.course.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {({ variants }) => (
          <div className="grid min-w-0 gap-4 lg:grid-cols-2">
            {targetLocales.map((locale) => (
              <CourseLocalizationCard
                key={locale.tag}
                courseId={course.id}
                locale={locale.tag}
                variant={variants.find((variant) => variant.locale === locale.tag) ?? null}
              />
            ))}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
