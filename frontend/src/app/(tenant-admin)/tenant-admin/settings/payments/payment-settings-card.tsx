import type { ReactNode } from "react";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * One section of Settings → Payments (SET-PAY-01): Klass-style card with a
 * title, an optional status badge or action in the header, and its own body.
 * Each section owns its data fetch and loading/error/permission states.
 */
export function PaymentSettingsCard({
  id,
  title,
  description,
  action,
  children,
  testId,
}: {
  id: string;
  title: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  const headingId = `${id}-heading`;
  return (
    <section aria-labelledby={headingId} data-testid={testId}>
      <Card>
        <CardHeader className="border-b border-border pb-4">
          <CardTitle>
            <h2 id={headingId}>{title}</h2>
          </CardTitle>
          {description ? <CardDescription>{description}</CardDescription> : null}
          {action ? <CardAction>{action}</CardAction> : null}
        </CardHeader>
        <CardContent>{children}</CardContent>
      </Card>
    </section>
  );
}
