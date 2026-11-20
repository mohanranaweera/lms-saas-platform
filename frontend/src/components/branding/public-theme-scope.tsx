import type { CSSProperties, ReactNode } from "react";
import { cn } from "@/lib/utils";
import { publicFont, SCRIPT_FALLBACK_FONT_CLASSES } from "@/lib/fonts/public-fonts";
import { resolveThemeColors, themeScopeCssVariables } from "@/lib/theme/color";

export interface PublicThemeValues {
  primaryColor?: string | null;
  accentColor?: string | null;
  backgroundColor?: string | null;
  surfaceColor?: string | null;
  textColor?: string | null;
  headingFont?: string | null;
  bodyFont?: string | null;
}

/**
 * The ONE tenant theming pipeline (replaces the old `TenantThemeProvider`,
 * which wrote to `document.documentElement` and would have restyled admin
 * screens). Renders a wrapper `div.tenant-public-theme` and sets every tenant
 * token as an inline custom property on that element only — so shadcn
 * utilities (`bg-primary`, `text-muted-foreground`, …, which Tailwind v4's
 * `@theme inline` resolves to `var(--primary)` etc. at the usage site) pick up
 * tenant colors inside the wrapper and nowhere else. Destructive/success/
 * warning tokens are deliberately left untouched.
 *
 * Used by the public layout AND by the Appearance settings live preview, so
 * the preview can never drift from the live site (.claude/rules/ui-ux.md §2).
 * Null/invalid values fall back to the documented defaults.
 */
export function PublicThemeScope({
  theme,
  children,
  className,
  as: Tag = "div",
  ...rest
}: {
  theme: PublicThemeValues | null | undefined;
  children: ReactNode;
  className?: string;
  as?: "div" | "section";
} & Omit<React.HTMLAttributes<HTMLDivElement>, "style" | "className" | "children">) {
  const colors = resolveThemeColors(theme);
  const heading = publicFont(theme?.headingFont);
  const body = publicFont(theme?.bodyFont);
  const style = {
    ...themeScopeCssVariables(colors),
    "--font-heading": heading.stack,
    "--font-body": body.stack,
    "--font-sans": body.stack,
  } as CSSProperties;

  return (
    <Tag
      {...rest}
      data-heading-font={heading.id}
      data-body-font={body.id}
      className={cn(
        "tenant-public-theme",
        heading.variableClass,
        body.variableClass,
        SCRIPT_FALLBACK_FONT_CLASSES,
        className
      )}
      style={style}
    >
      {children}
    </Tag>
  );
}
