package com.lms.liveclassmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.liveclassmanagement.domain.ClassSession;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006/{@code .claude/rules/tenancy.md}. Every custom
 * finder below is a default method built on {@link
 * org.springframework.data.jpa.domain.Specification}-backed {@code
 * findOne}/{@code findAll} (inherited from {@link TenantAwareRepository}),
 * never a Spring Data derived-query method - the latter would bypass {@code
 * TenantAwareRepositoryImpl}'s tenant-scoping override, mirroring {@code
 * coursemanagement.course.repository.CourseRepository}'s established
 * pattern exactly.
 *
 * <p>{@link #findByProviderReferenceAcrossTenantsForUpdate} is the ONE
 * deliberate, explicitly-named exception - the live-class webhook has no
 * subdomain/JWT and therefore no {@code TenantContext} at all when it needs
 * to resolve a session by {@code providerReference}, mirroring {@code
 * paymentmanagement.payment.repository.PaymentRepository
 * #findByGatewayReferenceAcrossTenantsForUpdate}'s identical rationale and
 * pessimistic-locking shape.
 */
public interface ClassSessionRepository extends TenantAwareRepository<ClassSession, UUID> {

	default List<ClassSession> findByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId),
				Sort.by(Sort.Direction.ASC, "scheduledStart"));
	}

	default List<ClassSession> findByTeacherId(UUID teacherId) {
		return findAll((root, query, cb) -> cb.equal(root.get("teacherId"), teacherId),
				Sort.by(Sort.Direction.ASC, "scheduledStart"));
	}

	default List<ClassSession> findByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("courseId").in(courseIds),
				Sort.by(Sort.Direction.ASC, "scheduledStart"));
	}

	default List<ClassSession> findAllForTenant() {
		return findAll(Sort.by(Sort.Direction.ASC, "scheduledStart"));
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM ClassSession s WHERE s.providerReference = :providerReference")
	Optional<ClassSession> findByProviderReferenceAcrossTenantsForUpdate(
			@Param("providerReference") String providerReference);

	/**
	 * Non-locking variant of {@link #findByProviderReferenceAcrossTenantsForUpdate}
	 * - used only for the webhook controller's pre-idempotency-insert tenant
	 * resolution ({@code LiveClassWebhookApi#resolveTenantId}), where holding
	 * a row lock is unnecessary overhead (the actual mutation later
	 * re-resolves with the locking variant).
	 */
	@Query("SELECT s FROM ClassSession s WHERE s.providerReference = :providerReference")
	Optional<ClassSession> findByProviderReferenceAcrossTenants(@Param("providerReference") String providerReference);

}
