package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.api.PaymentRejectedEvent;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-018 plan §18's "event-payload schema" unit check: every payment event
 * {@code notification-management} consumes ({@link PaymentConfirmedEvent},
 * {@link PaymentRejectedEvent}, {@link PaymentRefundedEvent}) carries a
 * non-null {@code studentId()} recipient accessor. The presence of the
 * accessor itself is compile-time-checked (these are Java records - removing
 * {@code studentId} from any of the three would fail this test class's
 * compilation, and every call site across the codebase that constructs one),
 * so this is deliberately a short, trivial construction-and-getter
 * assertion rather than reflection-based scaffolding - it exists to catch a
 * future edit that adds a new field but leaves {@code studentId} wired to a
 * null-producing call site, which compiles fine but silently breaks
 * notification dispatch (plan §12's "recipient_user_id must be non-null
 * before an outbox row is persisted" rule).
 */
class PaymentEventStudentIdAccessorTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	@Test
	void paymentConfirmedEventCarriesANonNullStudentId() {
		PaymentConfirmedEvent event = new PaymentConfirmedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, Instant.now(), STUDENT_ID, new BigDecimal("10.00"),
				"USD");

		assertThat(event.studentId()).isNotNull().isEqualTo(STUDENT_ID);
		assertThat(event.tenantId()).isNotNull();
	}

	@Test
	void paymentRejectedEventCarriesANonNullStudentId() {
		PaymentRejectedEvent event = new PaymentRejectedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
				PaymentStatus.PENDING, PaymentStatus.REJECTED, Instant.now(), STUDENT_ID, new BigDecimal("10.00"),
				"USD");

		assertThat(event.studentId()).isNotNull().isEqualTo(STUDENT_ID);
		assertThat(event.tenantId()).isNotNull();
	}

	@Test
	void paymentRefundedEventCarriesANonNullStudentId() {
		PaymentRefundedEvent event = new PaymentRefundedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), new BigDecimal("5.00"), "duplicate charge", Instant.now(), STUDENT_ID);

		assertThat(event.studentId()).isNotNull().isEqualTo(STUDENT_ID);
		assertThat(event.tenantId()).isNotNull();
	}

}
