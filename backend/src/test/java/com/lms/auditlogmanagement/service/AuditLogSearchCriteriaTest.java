package com.lms.auditlogmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Plain unit test, no Spring context - constructs {@link AuditLogSearchCriteria} directly. */
class AuditLogSearchCriteriaTest {

	@Test
	void fromAfterToThrowsInvalidAuditLogSearchException() {
		Instant from = Instant.now();
		Instant to = from.minusSeconds(60);

		assertThatThrownBy(() -> new AuditLogSearchCriteria(from, to, null, null))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void fromEqualToToIsAccepted() {
		Instant sameInstant = Instant.now();

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(sameInstant, sameInstant, null, null);

		assertThat(criteria.from()).isEqualTo(sameInstant);
		assertThat(criteria.to()).isEqualTo(sameInstant);
	}

	@Test
	void fromBeforeToIsAccepted() {
		Instant from = Instant.now().minusSeconds(60);
		Instant to = Instant.now();

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(from, to, "course.price_changed", "course");

		assertThat(criteria.from()).isEqualTo(from);
		assertThat(criteria.to()).isEqualTo(to);
		assertThat(criteria.action()).isEqualTo("course.price_changed");
		assertThat(criteria.targetEntity()).isEqualTo("course");
	}

	@Test
	void onlyFromSetIsAccepted() {
		Instant from = Instant.now();

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(from, null, null, null);

		assertThat(criteria.from()).isEqualTo(from);
		assertThat(criteria.to()).isNull();
	}

	@Test
	void onlyToSetIsAccepted() {
		Instant to = Instant.now();

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(null, to, null, null);

		assertThat(criteria.from()).isNull();
		assertThat(criteria.to()).isEqualTo(to);
	}

	@Test
	void allFieldsNullIsAccepted() {
		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(null, null, null, null);

		assertThat(criteria.from()).isNull();
		assertThat(criteria.to()).isNull();
		assertThat(criteria.action()).isNull();
		assertThat(criteria.targetEntity()).isNull();
	}

	@Test
	void blankActionThrowsInvalidAuditLogSearchException() {
		assertThatThrownBy(() -> new AuditLogSearchCriteria(null, null, "   ", null))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void emptyStringActionThrowsInvalidAuditLogSearchException() {
		assertThatThrownBy(() -> new AuditLogSearchCriteria(null, null, "", null))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void blankTargetEntityThrowsInvalidAuditLogSearchException() {
		assertThatThrownBy(() -> new AuditLogSearchCriteria(null, null, null, "   "))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void actionExceedingMaxLengthThrowsInvalidAuditLogSearchException() {
		String tooLong = "a".repeat(101);

		assertThatThrownBy(() -> new AuditLogSearchCriteria(null, null, tooLong, null))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void actionAtMaxLengthIsAccepted() {
		String maxLength = "a".repeat(100);

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(null, null, maxLength, null);

		assertThat(criteria.action()).isEqualTo(maxLength);
	}

	@Test
	void targetEntityExceedingMaxLengthThrowsInvalidAuditLogSearchException() {
		String tooLong = "a".repeat(101);

		assertThatThrownBy(() -> new AuditLogSearchCriteria(null, null, null, tooLong))
			.isInstanceOf(InvalidAuditLogSearchException.class);
	}

	@Test
	void targetEntityAtMaxLengthIsAccepted() {
		String maxLength = "a".repeat(100);

		AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(null, null, null, maxLength);

		assertThat(criteria.targetEntity()).isEqualTo(maxLength);
	}

}
