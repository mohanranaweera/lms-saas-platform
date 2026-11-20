/**
 * Hands the student to a form-POST payment gateway (PAY-GW-01, PayHere) by
 * submitting the backend-signed fields exactly as received. The frontend
 * never computes a hash or changes an amount.
 *
 * Defense in depth: the action must be one of PayHere's own checkout URLs, so
 * a tampered or unexpected response can never post the student's details to
 * another origin.
 */
const ALLOWED_GATEWAY_ACTIONS = new Set([
  "https://sandbox.payhere.lk/pay/checkout",
  "https://www.payhere.lk/pay/checkout",
]);

export function isAllowedGatewayAction(action: string): boolean {
  return ALLOWED_GATEWAY_ACTIONS.has(action);
}

export function submitGatewayForm(action: string, fields: Record<string, string>): void {
  if (!isAllowedGatewayAction(action)) {
    throw new Error("Unexpected payment page address.");
  }
  const form = document.createElement("form");
  form.method = "POST";
  form.action = action;
  form.style.display = "none";
  for (const [name, value] of Object.entries(fields)) {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = name;
    input.value = value;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
}
