package com.lms.usermanagement.student.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.student.domain.StudentRegistrationOtp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: every inherited finder
 * is AND-combined with the resolved tenant context by {@code
 * TenantAwareRepositoryImpl}.
 */
public interface StudentRegistrationOtpRepository extends TenantAwareRepository<StudentRegistrationOtp, UUID> {

	/** The most recent OTP row for (tenant, email) - used for both the rate-limit check on send and the verify lookup. */
	default Optional<StudentRegistrationOtp> findMostRecentByEmail(String email) {
		return findOne((root, query, cb) -> {
			query.orderBy(cb.desc(root.get("createdAt")));
			return cb.equal(root.get("email"), email);
		});
	}

	/** Every OTP row created for (tenant, email) since {@code since} - backs the send-rate-limit check. */
	default List<StudentRegistrationOtp> findAllByEmailCreatedSince(String email, Instant since) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("email"), email),
				cb.greaterThanOrEqualTo(root.get("createdAt"), since)), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

}
