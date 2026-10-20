"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { CreditCard } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PaymentComposerDialog } from "./payment-composer-dialog";
import type { ComposerPreset } from "./composer-model";

/**
 * Global PAY composer host for the student portal. One composer instance
 * lives at layout level so every entry point (header PAY, period "Pay now",
 * lesson "Pay now", Live Classes "Pay now") opens the same engine, and
 * closing it never disturbs the page underneath (expanded folders, selected
 * tab). Each open starts a fresh session (new idempotency keys).
 */
interface PaymentComposerContextValue {
  openComposer: (preset?: ComposerPreset) => void;
}

const PaymentComposerContext = createContext<PaymentComposerContextValue | null>(null);

export function PaymentComposerProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [preset, setPreset] = useState<ComposerPreset | null>(null);
  const [session, setSession] = useState(0);

  const openComposer = useCallback((next?: ComposerPreset) => {
    setPreset(next ?? null);
    setSession((current) => current + 1);
    setOpen(true);
  }, []);

  const value = useMemo(() => ({ openComposer }), [openComposer]);

  return (
    <PaymentComposerContext.Provider value={value}>
      {children}
      <PaymentComposerDialog key={session} open={open} onOpenChange={setOpen} preset={preset} />
    </PaymentComposerContext.Provider>
  );
}

/** Returns null outside the student portal (callers then hide their Pay now shortcut). */
export function usePaymentComposer(): PaymentComposerContextValue | null {
  return useContext(PaymentComposerContext);
}

/** The blue header PAY button - opens the composer with no preselection. */
export function GlobalPayButton() {
  const composer = usePaymentComposer();
  if (!composer) return null;
  return (
    <Button
      type="button"
      onClick={() => composer.openComposer()}
      data-testid="global-pay-button"
      aria-haspopup="dialog"
      className="rounded-full bg-blue-600 px-4 font-semibold text-white hover:bg-blue-700"
    >
      <CreditCard aria-hidden="true" />
      PAY
    </Button>
  );
}
