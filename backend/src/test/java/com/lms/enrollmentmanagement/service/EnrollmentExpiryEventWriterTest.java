package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEventType;
import com.lms.enrollmentmanagement.repository.EnrollmentExpiryEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mockito-only unit coverage for {@link EnrollmentExpiryEventWriter}, covering
 * exactly the idempotency/race-guard behavior extracted out of {@code
 * EnrollmentExpiryService} (see this class's own javadoc for why). Does not
 * and cannot test the {@code REQUIRES_NEW} transaction-isolation claim itself
 * at the unit level (no real Postgres transaction exists here) - that is
 * covered by {@code EnrollmentExpiryConcurrencyIntegrationTest}'s genuine
 * concurrent-Testcontainers run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollmentExpiryEventWriterTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ENROLLMENT_ID = UUID.randomUUID();

	@Mock
	private EnrollmentExpiryEventRepository enrollmentExpiryEventRepository;

	@Mock
	private TenantContext tenantContext;

	private EnrollmentExpiryEventWriter writer;

	@BeforeEach
	void setUp() {
		writer = new EnrollmentExpiryEventWriter(enrollmentExpiryEventRepository, tenantContext);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
	}

	/**
	 * {@link EnrollmentExpiryEventWriter#alreadyRecorded} is the cheap,
	 * no-new-transaction pre-check {@link EnrollmentExpiryService} calls
	 * before deciding whether to open a {@code REQUIRES_NEW} transaction at
	 * all - it delegates straight to the same {@code existsBy...} derived
	 * query used internally by {@link EnrollmentExpiryEventWriter#recordExpiryEventIfAbsent},
	 * and must never itself call {@code saveAndFlush}.
	 */
	@Test
	void alreadyRecordedReflectsTheExistenceCheckWithoutWritingAnything() {
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(ENROLLMENT_ID,
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(true);

		boolean result = writer.alreadyRecorded(ENROLLMENT_ID);

		assertThat(result).isTrue();
		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
	}

	@Test
	void alreadyRecordedReturnsFalseWhenNoEventExistsYet() {
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(ENROLLMENT_ID,
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false);

		boolean result = writer.alreadyRecorded(ENROLLMENT_ID);

		assertThat(result).isFalse();
		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
	}

	@Test
	void writesTheEventWhenNoneExistsYet() {
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(ENROLLMENT_ID,
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false);

		writer.recordExpiryEventIfAbsent(ENROLLMENT_ID);

		verify(enrollmentExpiryEventRepository).saveAndFlush(any());
	}

	@Test
	void neverWritesASecondEventOnceOneAlreadyExists() {
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(ENROLLMENT_ID,
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(true);

		writer.recordExpiryEventIfAbsent(ENROLLMENT_ID);

		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
	}

	/**
	 * The genuine concurrent-race case: the friendly pre-check said "absent"
	 * but a concurrent writer won first, so the insert itself violates {@code
	 * uq_enrollment_expiry_event_tenant_enrollment_type} (V22). Deliberately
	 * left to PROPAGATE, never caught here - see this class's own javadoc for
	 * why catching it inside this {@code REQUIRES_NEW} method (an earlier,
	 * reverted attempt) reintroduced a different bug ({@code
	 * UnexpectedRollbackException} on this method's own commit, since
	 * Hibernate already marks the underlying transaction rollback-only
	 * internally once a flush fails). {@link EnrollmentExpiryService} is the
	 * one that catches this, fully inside its own method body.
	 */
	@Test
	void propagatesADataIntegrityViolationFromALostRaceRatherThanSwallowingIt() {
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(ENROLLMENT_ID,
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false);
		when(enrollmentExpiryEventRepository.saveAndFlush(any()))
			.thenThrow(new DataIntegrityViolationException("uq_enrollment_expiry_event_tenant_enrollment_type"));

		assertThatThrownBy(() -> writer.recordExpiryEventIfAbsent(ENROLLMENT_ID))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
