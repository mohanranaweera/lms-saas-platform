"use client";

import { useSyncExternalStore } from "react";
import { Moon, Sun } from "lucide-react";
import { cn } from "@/lib/utils";
import { currentThemeMode, setThemeMode, subscribeThemeMode, type ThemeMode } from "@/lib/theme/theme-mode";

/**
 * Sun/moon light-dark switch (LOGIN-01 Phase A, Klass parity). The server
 * snapshot is "light"; `useSyncExternalStore` re-renders with the real mode
 * right after hydration, so the icon never causes a hydration mismatch. The
 * page itself is already in the right mode before paint (THEME_INIT_SCRIPT).
 */
export function ThemeToggle({ className }: { className?: string }) {
  const mode = useSyncExternalStore<ThemeMode>(subscribeThemeMode, currentThemeMode, () => "light");
  const dark = mode === "dark";

  return (
    <button
      type="button"
      onClick={() => setThemeMode(dark ? "light" : "dark")}
      aria-label="Dark theme"
      aria-pressed={dark}
      title={dark ? "Switch to light theme" : "Switch to dark theme"}
      data-testid="theme-toggle"
      className={cn(
        "inline-flex size-9 items-center justify-center rounded-lg text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
        className
      )}
    >
      {dark ? <Moon className="size-5" aria-hidden="true" /> : <Sun className="size-5" aria-hidden="true" />}
    </button>
  );
}
