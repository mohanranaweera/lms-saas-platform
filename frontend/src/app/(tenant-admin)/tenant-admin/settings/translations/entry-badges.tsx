"use client";

import { AlertTriangle, FilePen, Layers, PencilLine } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { useT } from "@/i18n/i18n-provider";
import type { TranslationEntry } from "@/lib/api/translations";

/**
 * Where the served value comes from, plus draft / review flags. Text + icon,
 * never colour alone (.claude/rules/ui-ux.md §4).
 */
export function EntryBadges({ entry }: { entry: TranslationEntry }) {
  const t = useT();
  return (
    <span className="flex flex-wrap gap-1.5" data-testid="entry-badges">
      {entry.origin === "OVERRIDE" ? (
        <Badge variant="default" data-badge="override">
          <PencilLine aria-hidden="true" />
          {t("i18nAdmin.badge.override")}
        </Badge>
      ) : entry.origin === "PLATFORM" ? (
        <Badge variant="outline" data-badge="inherited-platform">
          <Layers aria-hidden="true" />
          {t("i18nAdmin.badge.inheritedPlatform")}
        </Badge>
      ) : (
        <Badge variant="outline" data-badge="inherited-source">
          <Layers aria-hidden="true" />
          {t("i18nAdmin.badge.inheritedSource")}
        </Badge>
      )}
      {entry.draft ? (
        <Badge variant="secondary" data-badge="draft">
          <FilePen aria-hidden="true" />
          {entry.draft.action === "RESET" ? t("i18nAdmin.badge.draftReset") : t("i18nAdmin.badge.draft")}
        </Badge>
      ) : null}
      {entry.overrideNeedsReview || entry.platformStatus === "NEEDS_REVIEW" ? (
        <Badge variant="warning" data-badge="needs-review">
          <AlertTriangle aria-hidden="true" />
          {t("i18nAdmin.badge.needsReview")}
        </Badge>
      ) : null}
      {entry.platformStatus === "MISSING" && entry.origin === "SOURCE" ? (
        <Badge variant="destructive" data-badge="missing">
          {t("i18nAdmin.badge.missing")}
        </Badge>
      ) : null}
    </span>
  );
}
