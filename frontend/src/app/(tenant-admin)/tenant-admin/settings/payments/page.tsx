"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canViewInstituteConfig, canViewPaymentGateway } from "@/lib/auth/permissions";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { BankDetailsSection } from "./bank-details-section";
import { CurrencySection } from "./currency-section";
import { FacilityFeeSection } from "./facility-fee-section";
import { PayHereSection } from "./payhere-section";

/**
 * Settings → Payments (SET-PAY-01, Klass parity). Replaces the standalone
 * "Online payments" page at the same URL: currency (moved from General),
 * service/facility fee, the PayHere gateway (PAY-GW-01, unchanged) and the
 * institute bank account with the bank-transfer switch.
 *
 * Each section fetches its own data and renders its own loading, error and
 * permission-denied state, so one failing read never hides the others.
 * Currency, fee and bank details are tenant config (`BRANDING_SETTINGS`);
 * PayHere is `PAYMENT_GATEWAY`. Which sections render is UX only (Finance
 * Staff hold `PAYMENT_GATEWAY` VIEW but no institute config, so they see the
 * gateway status alone); the backend re-checks every request.
 */
export default function PaymentSettingsPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const showConfig = canViewInstituteConfig(role);
  // A role with neither grant still gets the PayHere section: its real request
  // returns 403 and renders the permission-denied state, never an empty page.
  const showGateway = canViewPaymentGateway(role) || !showConfig;

  return (
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/payments"
        description="Currency, fees, online payments and the bank account students pay into."
      />

      <div className="flex flex-col gap-6">
        {showConfig ? <CurrencySection /> : null}
        {showConfig ? <FacilityFeeSection /> : null}
        {showGateway ? <PayHereSection /> : null}
        {showConfig ? <BankDetailsSection /> : null}
      </div>
    </div>
  );
}
