/**
 * The billing address form moved to `components/students/billing-address-form.tsx`
 * so the payment composer can reuse it inline as the postage delivery address.
 * Re-exported here so My Profile keeps importing it from its own folder.
 */
export { BillingAddressForm } from "@/components/students/billing-address-form";
