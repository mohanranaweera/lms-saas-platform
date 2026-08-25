package com.lms.paymentmanagement.slip.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Structural/serialization coverage (Medium review finding, item 5):
 * {@link PaymentSlipResponse} must actually carry and serialize the
 * reviewer-facing enrichment fields (student/reviewer email, order
 * amount/currency) added alongside {@code PaymentSlipView} - a reviewer
 * currently has zero on-screen way to identify who a slip belongs to or
 * cross-check the expected amount without these. {@code
 * SlipRequestDtoShapeTest} (a separate file) covers the opposite concern -
 * that the client-facing REQUEST DTOs never expose a server-resolved field -
 * so this is deliberately a new, separate test class rather than an
 * extension of that one.
 */
class PaymentSlipResponseShapeTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

	@Test
	void paymentSlipResponseSerializesStudentAndReviewerEmailAndOrderAmountAndCurrency() throws Exception {
		PaymentSlipResponse response = new PaymentSlipResponse(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "REF-123", PaymentSlipStatus.APPROVED, Instant.now(), UUID.randomUUID(),
				Instant.now(), List.of(), "student@example.test", "finance@example.test", new BigDecimal("199.99"),
				"USD");

		String json = OBJECT_MAPPER.writeValueAsString(response);

		assertThat(json).contains("\"studentEmail\":\"student@example.test\"")
			.contains("\"reviewerEmail\":\"finance@example.test\"")
			.contains("\"orderAmount\":199.99")
			.contains("\"orderCurrency\":\"USD\"");
	}

	@Test
	void paymentSlipResponseSerializesANullReviewerEmailWhenNotYetReviewed() throws Exception {
		PaymentSlipResponse response = new PaymentSlipResponse(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "REF-456", PaymentSlipStatus.UNDER_REVIEW, Instant.now(), null, null, List.of(),
				"student@example.test", null, new BigDecimal("50.00"), "USD");

		String json = OBJECT_MAPPER.writeValueAsString(response);

		assertThat(json).contains("\"reviewerEmail\":null").contains("\"studentEmail\":\"student@example.test\"");
	}

}
