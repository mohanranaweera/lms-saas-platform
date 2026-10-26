package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.attendancemanagement.api.AttendanceReportQueryApi;
import com.lms.attendancemanagement.repository.AttendanceReportFactRepository;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant- and permission-guarded attendance facts for reporting-analytics. */
@Service
public class AttendanceReportQueryService implements AttendanceReportQueryApi {

	private final AttendanceReportFactRepository factRepository;

	private final AttendanceAccessGuard accessGuard;

	private final PermissionCheckService permissionCheckService;

	public AttendanceReportQueryService(AttendanceReportFactRepository factRepository,
			AttendanceAccessGuard accessGuard, PermissionCheckService permissionCheckService) {
		this.factRepository = factRepository;
		this.accessGuard = accessGuard;
		this.permissionCheckService = permissionCheckService;
	}

	@Override
	@Transactional(readOnly = true)
	public List<AttendanceReportFact> findReportFacts(UUID courseId, Instant fromInclusive, Instant toExclusive,
			int maxFacts) {
		accessGuard.requireClassSessionPreLookup(PermissionAction.VIEW);
		accessGuard.requireCourseOwnershipIfTeacher(courseId);
		return factRepository.findByCourseAndOccurrenceBetween(courseId, fromInclusive, toExclusive, maxFacts);
	}

	@Override
	@Transactional(readOnly = true)
	public List<AttendanceReportFact> findReportFactsForFinancialReport(UUID courseId, Instant fromInclusive,
			Instant toExclusive, int maxFacts) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		return factRepository.findByCourseAndOccurrenceBetween(courseId, fromInclusive, toExclusive, maxFacts);
	}

}
