package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.domain.AttendanceSheet;
import com.lms.attendancemanagement.repository.AttendanceSheetRepository;
import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds {@link AttendanceRecordView}s with their Wave 8 sheet/session
 * context in two batched reads per page (sheets by id via the tenant-scoped
 * repository, then class-session titles via {@link ClassSessionLookupApi})
 * - never one lookup per row. Callers must invoke it inside their own
 * (read-only) transaction.
 */
@Component
public class AttendanceRecordViewAssembler {

	private final AttendanceSheetRepository attendanceSheetRepository;

	private final ClassSessionLookupApi classSessionLookupApi;

	public AttendanceRecordViewAssembler(AttendanceSheetRepository attendanceSheetRepository,
			ClassSessionLookupApi classSessionLookupApi) {
		this.attendanceSheetRepository = attendanceSheetRepository;
		this.classSessionLookupApi = classSessionLookupApi;
	}

	public List<AttendanceRecordView> toViews(Collection<AttendanceRecord> records) {
		if (records.isEmpty()) {
			return List.of();
		}
		Set<UUID> sheetIds = records.stream().map(AttendanceRecord::getSheetId).collect(Collectors.toSet());
		Map<UUID, AttendanceSheet> sheets = attendanceSheetRepository.findAllById(sheetIds)
			.stream()
			.collect(Collectors.toMap(AttendanceSheet::getId, Function.identity()));
		Set<UUID> sessionIds = sheets.values()
			.stream()
			.map(AttendanceSheet::getClassSessionId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, ClassSessionSummary> sessions = classSessionLookupApi.getSessionSummaries(sessionIds);
		return records.stream().map(record -> {
			AttendanceSheet sheet = sheets.get(record.getSheetId());
			ClassSessionSummary session = sheet != null && sheet.getClassSessionId() != null
					? sessions.get(sheet.getClassSessionId()) : null;
			return toView(record, sheet, session);
		}).toList();
	}

	/** Single-record variant for a caller that already holds the sheet and session. */
	public static AttendanceRecordView toView(AttendanceRecord record, AttendanceSheet sheet,
			ClassSessionSummary session) {
		return new AttendanceRecordView(record.getId(), record.getSheetId(), sheet != null ? sheet.getSource() : null,
				record.getCourseId(), sheet != null ? sheet.getClassSessionId() : null,
				session != null ? session.title() : null, record.getSessionId(), record.getStudentId(),
				record.getStatus(), record.getMarkedBy(), record.getMarkedAt(), record.getCreatedAt(),
				record.getUpdatedAt());
	}

}
