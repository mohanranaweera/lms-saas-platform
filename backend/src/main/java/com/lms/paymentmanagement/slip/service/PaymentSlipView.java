package com.lms.paymentmanagement.slip.service;

import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service-layer read model - never a {@code PaymentSlip} JPA entity exposed
 * from a controller. {@code flags} is always the FULL flag history (never
 * filtered to latest-only), per spec 25's "never clear/overwrite a prior
 * flag" rule and plan §10's "full flag history" API contract requirement.
 *
 * <p>{@code studentEmail}/{@code reviewerEmail}/{@code orderAmount}/{@code
 * orderCurrency} are enrichment fields (Medium review finding) so a reviewer
 * has an on-screen way to identify who a slip belongs to and cross-check the
 * expected amount - {@code orderAmount}/{@code orderCurrency} are a read of
 * the same-domain {@code student_order} row this slip is evidence of payment
 * for. {@code reviewerEmail} is {@code null} until the slip has been
 * reviewed (mirrors {@code reviewerId}). {@code studentEmail} is expected to
 * be non-null in practice - {@code payment_slip.student_id} is FK-backed, so
 * a matching {@code tenant_user} row should always exist - but it is
 * resolved via a best-effort {@link
 * com.lms.identityaccessservice.api.UserProvisioningApi#findTenantUserSummaries}
 * batch lookup that falls through to {@code null} if no match is found; this
 * is NOT a schema-enforced guarantee of this view, so callers must still
 * treat it as nullable.
 */
public record PaymentSlipView(UUID id, UUID orderId, UUID studentId, String referenceNumber,
		PaymentSlipStatus status, Instant submittedAt, UUID reviewerId, Instant reviewedAt,
		List<PaymentSlipFlagView> flags, String studentEmail, String reviewerEmail, BigDecimal orderAmount,
		String orderCurrency) {

}
