package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Plain unit test, no Spring context - {@link AuditLogSpecifications} is a
 * pure static-method utility class. Mirrors the intended assertion technique
 * for {@code CourseSpecifications} (no such test existed yet in this
 * codebase to copy verbatim, so this uses the most direct check available:
 * a {@code null} filter argument must return the exact same singleton
 * instance {@link Specification#unrestricted()} itself returns - a
 * non-capturing lambda expression is cached by the JVM per lambda call site,
 * so every {@code Specification.unrestricted()} call yields the identical
 * instance, making reference equality a valid and precise assertion here).
 */
class AuditLogSpecificationsTest {

	@Test
	void occurredAtFromNullReturnsUnrestricted() {
		assertThat(AuditLogSpecifications.occurredAtFrom(null)).isSameAs(Specification.unrestricted());
	}

	@Test
	void occurredAtFromNonNullReturnsRestrictedSpecification() {
		Specification<?> spec = AuditLogSpecifications.occurredAtFrom(Instant.now());
		assertThat(spec).isNotSameAs(Specification.unrestricted());
	}

	@Test
	void occurredAtToNullReturnsUnrestricted() {
		assertThat(AuditLogSpecifications.occurredAtTo(null)).isSameAs(Specification.unrestricted());
	}

	@Test
	void occurredAtToNonNullReturnsRestrictedSpecification() {
		Specification<?> spec = AuditLogSpecifications.occurredAtTo(Instant.now());
		assertThat(spec).isNotSameAs(Specification.unrestricted());
	}

	@Test
	void withActionNullReturnsUnrestricted() {
		assertThat(AuditLogSpecifications.withAction(null)).isSameAs(Specification.unrestricted());
	}

	@Test
	void withActionNonNullReturnsRestrictedSpecification() {
		Specification<?> spec = AuditLogSpecifications.withAction("course.price_changed");
		assertThat(spec).isNotSameAs(Specification.unrestricted());
	}

	@Test
	void withTargetEntityNullReturnsUnrestricted() {
		assertThat(AuditLogSpecifications.withTargetEntity(null)).isSameAs(Specification.unrestricted());
	}

	@Test
	void withTargetEntityNonNullReturnsRestrictedSpecification() {
		Specification<?> spec = AuditLogSpecifications.withTargetEntity("course");
		assertThat(spec).isNotSameAs(Specification.unrestricted());
	}

}
