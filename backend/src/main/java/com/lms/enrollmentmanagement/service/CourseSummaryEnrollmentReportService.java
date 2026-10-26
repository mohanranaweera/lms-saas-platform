package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.FreeCardFact;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.ParticipationFact;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.domain.FreeCard;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.enrollmentmanagement.repository.FreeCardRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped RPT-05 facts, sourced only from structurally filtered repositories. */
@Service
public class CourseSummaryEnrollmentReportService implements CourseSummaryEnrollmentReportApi {

	private final CourseParticipationRepository participationRepository;

	private final FreeCardRepository freeCardRepository;

	public CourseSummaryEnrollmentReportService(CourseParticipationRepository participationRepository,
			FreeCardRepository freeCardRepository) {
		this.participationRepository = participationRepository;
		this.freeCardRepository = freeCardRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ParticipationFact> findParticipationFacts(UUID courseId, Instant fromInclusive, Instant toExclusive,
			int maxFacts) {
		return participationRepository.findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.greaterThanOrEqualTo(root.get("enrolledAt"), fromInclusive),
				cb.lessThan(root.get("enrolledAt"), toExclusive)))
			.stream()
			.sorted(java.util.Comparator.comparing(CourseParticipation::getEnrolledAt).thenComparing(CourseParticipation::getId))
			.limit(Math.max(1L, (long) maxFacts + 1))
			.map(row -> new ParticipationFact(row.getId(), row.getStudentId(), row.getEnrolledAt(), row.getSource().name()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<FreeCardFact> findFreeCardFacts(UUID courseId, LocalDate fromInclusive, LocalDate toInclusive,
			int maxFacts) {
		return freeCardRepository.findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.lessThanOrEqualTo(root.<LocalDate>get("validFrom"), toInclusive),
				cb.greaterThanOrEqualTo(root.<LocalDate>get("validTo"), fromInclusive)))
			.stream()
			.sorted(java.util.Comparator.comparing(FreeCard::getValidFrom).thenComparing(FreeCard::getId))
			.limit(Math.max(1L, (long) maxFacts + 1))
			.map(row -> new FreeCardFact(row.getId(), row.getStudentId(), row.getValidFrom(), row.getValidTo(),
					row.getCurrency(), row.getNominalMonthlyAmount(), row.getNominalValue(), row.isRevoked()))
			.toList();
	}

}
