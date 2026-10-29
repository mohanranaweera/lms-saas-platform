package com.lms.ledgersettlementmanagement.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PAY-HUB-01: the ledger-derived {@link PaymentOperationalState} of orders in
 * the caller's own tenant - the same resolution {@code /ledger/dashboard} and
 * {@code /ledger/outstanding} use - for other modules' read views (the
 * payment-management order detail). Read-only; an order id outside the
 * current tenant is simply absent from the result.
 */
public interface OrderPaymentStateApi {

	Map<UUID, PaymentOperationalState> resolveForOrders(List<UUID> orderIds);

}
