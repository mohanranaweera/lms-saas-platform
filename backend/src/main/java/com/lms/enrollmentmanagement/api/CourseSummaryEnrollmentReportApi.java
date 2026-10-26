package com.lms.enrollmentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RPT-05 bounded, tenant-scoped facts for a Course Summary Report. The caller
 * never supplies a tenant id. These facts are descriptive only and do not grant
 * or revoke enrollment, entitlement, or Free Card access.
 */
public interface CourseSummaryEnrollmentReportApi {

	List<ParticipationFact> findParticipationFacts(UUID courseId, Instant fromInclusive, Instant toExclusive,
			int maxFacts);

	List<FreeCardFact> findFreeCardFacts(UUID courseId, LocalDate fromInclusive, LocalDate toInclusive, int maxFacts);

	record ParticipationFact(UUID participationId, UUID studentId, Instant enrolledAt, String source) {

	}

	record FreeCardFact(UUID cardId, UUID studentId, LocalDate validFrom, LocalDate validTo, String currency,
			BigDecimal nominalMonthlyAmount, BigDecimal nominalValue, boolean revoked) {

	}

}
