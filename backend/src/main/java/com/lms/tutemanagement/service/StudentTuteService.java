package com.lms.tutemanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.service.TuteViews.StudentTuteView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-02: the student's own "Tute Delivery" tab of a course. Read-only; the student is always
 * the authenticated caller and the tenant the resolved context (the repository is tenant-scoped).
 * An unknown / cross-tenant course is 404; another student's copies are never returned.
 */
@Service
@Transactional(readOnly = true)
public class StudentTuteService {

	static final String ON_HOLD = "ON_HOLD";

	private final CourseLookupApi courseLookupApi;

	private final TuteFulfillmentRepository fulfillmentRepository;

	public StudentTuteService(CourseLookupApi courseLookupApi, TuteFulfillmentRepository fulfillmentRepository) {
		this.courseLookupApi = courseLookupApi;
		this.fulfillmentRepository = fulfillmentRepository;
	}

	public List<StudentTuteView> myTutes(UUID courseId) {
		if (courseLookupApi.getTeacherId(courseId).isEmpty()) {
			throw new NotFoundException("Course not found");
		}
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		return fulfillmentRepository.findActiveByCourseIdAndStudentId(courseId, studentId)
			.stream()
			.map(StudentTuteService::view)
			.toList();
	}

	static StudentTuteView view(TuteFulfillment row) {
		String status = row.getStatus() == FulfillmentStatus.PENDING && row.isHeld() ? ON_HOLD
				: row.getStatus().name();
		return new StudentTuteView(row.getId(), row.getTuteName(), status, row.getMethod().name(), row.getCreatedAt(),
				row.getDispatchedAt());
	}

}
