package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEvent;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEventType;
import com.lms.enrollmentmanagement.repository.EnrollmentExpiryEventRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal collaborator of {@link EnrollmentExpiryService} - NOT exposed via
 * this module's {@code api} package, never injected by another domain
 * (package-private, mirroring {@code ReactivationTransactionService}'s
 * established convention for a single-purpose collaborator).
 *
 * <h2>Why this method runs in its OWN {@code Propagation.REQUIRES_NEW} transaction</h2>
 * Extracted from a bug found by {@code EnrollmentExpiryConcurrencyIntegrationTest}
 * (real Testcontainers Postgres, genuinely concurrent readers racing past the
 * same enrollment's expiry): the guarded insert below can genuinely lose a
 * race under real concurrent load - that is its whole reason to exist, not an
 * edge case. When it loses, Postgres aborts the CURRENT transaction at the
 * statement level - a plain {@code catch (DataIntegrityViolationException)}
 * inside the CALLER's ambient transaction cannot recover from that (the
 * caller's next statement in the same transaction still fails). {@code
 * Propagation.REQUIRES_NEW} opens a genuinely separate physical transaction
 * (suspending, not sharing, the caller's), so a losing thread's abort is
 * fully contained to THIS method's own transaction - the caller's suspended
 * transaction resumes untouched once this method returns (or throws).
 *
 * <h2>Why the {@code DataIntegrityViolationException} is NOT caught inside this method</h2>
 * A first attempt caught it here, inside this method's own {@code
 * @Transactional} body. That looked right but reintroduced a DIFFERENT bug,
 * also only reproducible under genuine Testcontainers concurrency: once
 * {@code saveAndFlush} throws (a translated Hibernate {@code
 * PersistenceException} from the failed flush), Hibernate has ALREADY marked
 * the underlying {@code EntityTransaction} rollback-only internally - as part
 * of the JPA spec's own exception-handling contract, independent of whether
 * application code catches the translated Spring exception. Catching it here
 * and returning normally therefore does not "recover" the transaction - it
 * only hides the failure from Spring's own {@code TransactionInterceptor}
 * long enough for it to attempt a COMMIT on a transaction Hibernate has
 * already condemned, which Spring's {@code AbstractPlatformTransactionManager}
 * detects via {@code status.isGlobalRollbackOnly()} and turns into a very
 * visible {@link org.springframework.transaction.UnexpectedRollbackException}
 * ("Transaction silently rolled back because it has been marked as
 * rollback-only") - a 500, just a different one than the bug this class fixes.
 *
 * <p>Letting the exception propagate OUT of this method instead is the
 * correct fix: {@code TransactionAspectSupport} sees an exception escape the
 * method body and calls {@code rollback()} (not {@code commit()}) on this
 * REQUIRES_NEW transaction - a rollback that was always going to happen
 * anyway, now performed the way Spring expects, which never throws {@code
 * UnexpectedRollbackException}. {@link EnrollmentExpiryService#resolveAccessState}
 * catches this exception at ITS OWN layer instead (fully inside its own
 * method body, never re-thrown) - mirroring {@code
 * ReactivationTransactionService}'s documented rule that Spring only marks a
 * transaction rollback-only when an exception escapes THAT method's own
 * proxy boundary; since the catch there never lets it escape, {@code
 * resolveAccessState}'s own (separate, already-healthy) transaction is
 * unaffected.
 *
 * <p>Unlike {@code ReactivationTransactionService} - which deliberately does
 * NOT use {@code REQUIRES_NEW}, because enrollment activation/reactivation
 * must commit atomically with the payment/slip confirmation that authorized
 * it (see that class's own javadoc) - this write has no such atomicity
 * requirement. Per {@code docs/architecture/enrollment-access.md} §7, {@code
 * enrollment_expiry_event} is explicitly a "lazy," best-effort, idempotent
 * side-effect record, not itself an audit-log entry and not required to be
 * atomic with the access-state read that triggers it. Committing it
 * independently, slightly before the caller's own transaction, is therefore
 * safe: the worst case on a subsequent failure elsewhere in the caller is one
 * extra, harmless, already-idempotent expiry-event row - never a partially
 * written financial/activation record.
 */
@Service
class EnrollmentExpiryEventWriter {

	private final EnrollmentExpiryEventRepository enrollmentExpiryEventRepository;

	private final TenantContext tenantContext;

	EnrollmentExpiryEventWriter(EnrollmentExpiryEventRepository enrollmentExpiryEventRepository,
			TenantContext tenantContext) {
		this.enrollmentExpiryEventRepository = enrollmentExpiryEventRepository;
		this.tenantContext = tenantContext;
	}

	/**
	 * Cheap, read-only existence check with NO transaction propagation
	 * requirement of its own - runs in whatever transaction is already
	 * active (including none), unlike {@link #recordExpiryEventIfAbsent}
	 * which always pays for a full {@code REQUIRES_NEW} transaction
	 * begin/commit and connection checkout. Intended as a pre-check the
	 * caller uses to skip straight to "already recorded, nothing to do" in
	 * the overwhelmingly common case, without needing a new transaction at
	 * all for a plain derived-query read.
	 *
	 * <p>This is purely an optimization, not a substitute for the guarded
	 * insert's own correctness: a race can still occur between this call
	 * returning {@code false} and {@link #recordExpiryEventIfAbsent}'s own
	 * insert, which is why that method keeps its own internal existence
	 * check and unique-constraint guard regardless of what this method
	 * returned.
	 *
	 * @return {@code true} if an {@code EXPIRED} {@code enrollment_expiry_event}
	 * row already exists for {@code enrollmentId}.
	 */
	boolean alreadyRecorded(UUID enrollmentId) {
		return enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollmentId,
				EnrollmentExpiryEventType.EXPIRED);
	}

	/**
	 * The first time a live check observes {@code enrollment} as expired,
	 * writes one guarded, idempotent {@code enrollment_expiry_event} row.
	 * Never a mutation of {@code enrollment} itself, and never itself an
	 * audit-log entry (plan §16 - see {@link EnrollmentExpiryEvent}'s
	 * javadoc).
	 *
	 * <p>Keeps its own internal {@link #alreadyRecorded(UUID)}-equivalent
	 * existence check even though callers are expected to pre-check via
	 * {@link #alreadyRecorded(UUID)} first - the pre-check is an
	 * optimization only, and a race between the two calls is expected and
	 * safely handled by the unique constraint below plus the caller's own
	 * {@code DataIntegrityViolationException} handling.
	 *
	 * @throws DataIntegrityViolationException when this call loses a genuine
	 * concurrent race - deliberately left uncaught here; see class javadoc
	 * for why the caller must be the one to catch it.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void recordExpiryEventIfAbsent(UUID enrollmentId) {
		if (enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollmentId,
				EnrollmentExpiryEventType.EXPIRED)) {
			return;
		}
		EnrollmentExpiryEvent event = EnrollmentExpiryEvent.expired(tenantContext.getTenantId(), enrollmentId);
		// saveAndFlush (not save) is load-bearing (H2 fix, MVP-012 review): a
		// plain save() here would not be flushed inside this method, so a
		// genuine constraint violation from a real concurrent race would
		// surface later (at commit-time auto-flush) rather than here, where
		// the caller expects it.
		enrollmentExpiryEventRepository.saveAndFlush(event);
	}

}
