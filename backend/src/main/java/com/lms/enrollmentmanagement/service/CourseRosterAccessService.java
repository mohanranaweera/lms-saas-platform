package com.lms.enrollmentmanagement.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.RosterAccessStatus;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.domain.ContentEntitlementScope;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.FreeCard;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.FreeCardRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The course roster's "paid access right now" column (ADR-028, plan O-4). One
 * course-wide read per source - legacy enrollment windows (extensions and grace
 * via {@link AccessWindowCalculator}), unrevoked entitlements, unrevoked Free
 * Cards - all through the structurally tenant-filtered repositories.
 *
 * <p>A MONTHLY entitlement counts only for the CURRENT calendar month in the
 * tenant timezone: a still-valid entitlement for last month does not mean the
 * student has paid this month. Display only - never an authorization input.
 */
@Service
public class CourseRosterAccessService {

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentRepository enrollmentRepository;

	private final AccessWindowCalculator accessWindowCalculator;

	private final ContentEntitlementRepository entitlementRepository;

	private final FreeCardRepository freeCardRepository;

	private final TenantZoneLookup tenantZoneLookup;

	public CourseRosterAccessService(CourseLookupApi courseLookupApi, EnrollmentRepository enrollmentRepository,
			AccessWindowCalculator accessWindowCalculator, ContentEntitlementRepository entitlementRepository,
			FreeCardRepository freeCardRepository, TenantZoneLookup tenantZoneLookup) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentRepository = enrollmentRepository;
		this.accessWindowCalculator = accessWindowCalculator;
		this.entitlementRepository = entitlementRepository;
		this.freeCardRepository = freeCardRepository;
		this.tenantZoneLookup = tenantZoneLookup;
	}

	@Transactional(readOnly = true)
	public Map<UUID, RosterAccessStatus> accessStatuses(UUID courseId, Collection<UUID> studentIds) {
		Map<UUID, RosterAccessStatus> statuses = new HashMap<>();
		boolean freeCourse = courseLookupApi.findCatalogInfo(courseId)
			.map(info -> "FREE".equals(info.pricingModel()))
			.orElse(false);
		if (freeCourse) {
			studentIds.forEach(id -> statuses.put(id, RosterAccessStatus.FREE_COURSE));
			return statuses;
		}

		Instant now = Instant.now();
		YearMonth thisMonth = YearMonth.from(now.atZone(tenantZoneLookup.currentTenantZone()));
		Set<UUID> paid = new HashSet<>(legacyPaid(courseId, now));
		for (ContentEntitlement entitlement : entitlementRepository.findUnrevokedByCourseId(courseId)) {
			if (entitlement.isActiveAt(now) && coversNow(entitlement, thisMonth)) {
				paid.add(entitlement.getStudentId());
			}
		}
		Set<UUID> freeCard = new HashSet<>();
		for (FreeCard card : freeCardRepository.findUnrevokedByCourseId(courseId)) {
			if (card.isEffectiveAt(now) && card.coveredMonths().contains(thisMonth)) {
				freeCard.add(card.getStudentId());
			}
		}
		for (UUID studentId : studentIds) {
			statuses.put(studentId, paid.contains(studentId) ? RosterAccessStatus.PAID
					: freeCard.contains(studentId) ? RosterAccessStatus.FREE_CARD : RosterAccessStatus.NOT_PAID);
		}
		return statuses;
	}

	private List<UUID> legacyPaid(UUID courseId, Instant now) {
		List<Enrollment> current = enrollmentRepository.findAllLineageCurrentByCourseId(courseId);
		Map<UUID, AccessWindowCalculator.AccessWindow> windows = accessWindowCalculator.windowsFor(current);
		return current.stream()
			.filter(enrollment -> accessWindowCalculator.isCurrentlyActive(enrollment, windows.get(enrollment.getId()),
					now))
			.map(Enrollment::getStudentId)
			.toList();
	}

	private static boolean coversNow(ContentEntitlement entitlement, YearMonth thisMonth) {
		if (entitlement.getScope() != ContentEntitlementScope.MONTHLY) {
			return true;
		}
		return entitlement.getPeriodYear() != null && entitlement.getPeriodMonth() != null
				&& YearMonth.of(entitlement.getPeriodYear(), entitlement.getPeriodMonth()).equals(thisMonth);
	}

}
