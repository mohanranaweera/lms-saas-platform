package com.lms.tutemanagement.repository;

import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.TutePaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Race-safe writes and locks for {@code tute_fulfillment} (TUTE-01). The tenant is always the
 * resolved {@link com.lms.common.tenant.TenantContext} - never a parameter.
 */
public interface TuteFulfillmentWrites {

	/** Everything a new PENDING row needs; {@code paymentId}/{@code orderId} only for PAYMENT rows. */
	record NewFulfillment(UUID tuteId, UUID courseId, UUID studentId, FulfillmentMethod method,
			FulfillmentSource source, UUID paymentId, UUID orderId, String tuteName, TutePaymentType paymentType,
			BigDecimal price, String currency, String orderDeliveryName, String orderDeliveryAddressLine,
			String orderDeliveryCity, String orderDeliveryPhone, UUID actorId, Instant now) {
	}

	/**
	 * {@code INSERT ... ON CONFLICT (tenant_id, tute_id, student_id) WHERE status <> 'CANCELLED'
	 * DO NOTHING} in the context tenant.
	 * @return {@code 1} if inserted, {@code 0} if the student already holds a non-cancelled copy.
	 */
	int insertIfAbsent(NewFulfillment row);

	/**
	 * Locks ({@code SELECT ... FOR UPDATE}, in id order) the context tenant's fulfillments among
	 * {@code ids}; ids of another tenant are simply not returned. Call before loading the rows.
	 */
	List<UUID> lockFulfillments(Collection<UUID> ids);

	/**
	 * Per tute of {@code tuteIds} (context tenant), the number of {@code PENDING} and {@code
	 * DISPATCHED} rows ({@code [pending, dispatched]}); tutes without rows are absent.
	 */
	Map<UUID, long[]> countAllocatedByTute(Collection<UUID> tuteIds);

}
