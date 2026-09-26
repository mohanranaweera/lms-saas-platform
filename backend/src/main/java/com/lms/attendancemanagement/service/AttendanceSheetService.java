package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceSheet;
import com.lms.attendancemanagement.repository.AttendanceSheetRepository;
import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Find-or-create for {@link AttendanceSheet} (Wave 8). Always joins the
 * caller's marking transaction ({@link Propagation#MANDATORY}) so a sheet is
 * only ever created as part of a successful mark - never by a read, and
 * never left behind by a mark that rolled back.
 *
 * <p>Creation is race-safe: the native {@code INSERT ... ON CONFLICT DO
 * NOTHING} blocks behind a concurrent inserter of the same session/lesson
 * and then no-ops, and the follow-up read (a new statement under READ
 * COMMITTED) sees whichever row won. {@code courseId} always comes from the
 * already-resolved, tenant-scoped session/lesson projection - never from the
 * client.
 */
@Service
public class AttendanceSheetService {

	private final AttendanceSheetRepository attendanceSheetRepository;

	private final TenantContext tenantContext;

	public AttendanceSheetService(AttendanceSheetRepository attendanceSheetRepository, TenantContext tenantContext) {
		this.attendanceSheetRepository = attendanceSheetRepository;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public Optional<AttendanceSheet> findByClassSessionId(UUID classSessionId) {
		return attendanceSheetRepository.findByClassSessionId(classSessionId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public AttendanceSheet ensureClassSessionSheet(ClassSessionSummary session, UUID actorId, Instant now) {
		Optional<AttendanceSheet> existing = attendanceSheetRepository.findByClassSessionId(session.id());
		if (existing.isPresent()) {
			return existing.get();
		}
		attendanceSheetRepository.insertClassSessionSheetIfAbsent(UuidV7Generator.generate(),
				tenantContext.getTenantId(), session.courseId(), session.id(), actorId, now);
		return attendanceSheetRepository.findByClassSessionId(session.id())
			.orElseThrow(() -> new IllegalStateException(
					"Attendance sheet insert did not persist a row for class session " + session.id()));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public AttendanceSheet ensureLegacySheet(UUID courseId, UUID lessonId, UUID actorId, Instant now) {
		Optional<AttendanceSheet> existing = attendanceSheetRepository.findLegacyByLessonId(lessonId);
		if (existing.isPresent()) {
			return existing.get();
		}
		attendanceSheetRepository.insertLegacySheetIfAbsent(UuidV7Generator.generate(), tenantContext.getTenantId(),
				courseId, lessonId, actorId, now);
		return attendanceSheetRepository.findLegacyByLessonId(lessonId)
			.orElseThrow(() -> new IllegalStateException(
					"Legacy attendance sheet insert did not persist a row for lesson " + lessonId));
	}

}
