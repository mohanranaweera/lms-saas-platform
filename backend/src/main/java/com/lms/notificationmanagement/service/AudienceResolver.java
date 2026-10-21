package com.lms.notificationmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.notificationmanagement.domain.Broadcast.AudienceType;
import com.lms.notificationmanagement.domain.Broadcast.PaymentStatusFilter;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an audience definition into concrete recipients for the current
 * tenant (Wave 11 §2.5). Every id - course, student - is resolved through the
 * owning module's tenant-scoped API, so a foreign id is either "not found"
 * (course) or rejected outright (selected students) - never a partial send to
 * an unexpected set. Suspended/invited accounts are always excluded.
 */
@Component
public class AudienceResolver {

	public static final int MAX_RECIPIENTS = 5000;

	private final StudentLookupApi studentLookupApi;

	private final TeacherLookupApi teacherLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final CourseLookupApi courseLookupApi;

	private final PaymentStatusApi paymentStatusApi;

	public AudienceResolver(StudentLookupApi studentLookupApi, TeacherLookupApi teacherLookupApi,
			EnrollmentAccessApi enrollmentAccessApi, CourseLookupApi courseLookupApi,
			PaymentStatusApi paymentStatusApi) {
		this.studentLookupApi = studentLookupApi;
		this.teacherLookupApi = teacherLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.courseLookupApi = courseLookupApi;
		this.paymentStatusApi = paymentStatusApi;
	}

	public enum RecipientKind {

		STUDENT, TEACHER

	}

	public record AudienceSpec(AudienceType type, UUID courseId, PaymentStatusFilter paymentStatus,
			List<UUID> studentIds) {

	}

	public record Recipient(UUID userId, String name, RecipientKind kind, boolean hasEmail, boolean hasMobile,
			boolean hasWhatsApp) {

	}

	@Transactional(readOnly = true)
	public List<Recipient> resolve(AudienceSpec spec) {
		if (spec == null || spec.type() == null) {
			throw invalid("audience.type", "is required");
		}
		List<Recipient> recipients = switch (spec.type()) {
			case ALL_STUDENTS -> {
				requireNoExtras(spec, false, false);
				yield students(studentLookupApi.listAllStudentUserIds());
			}
			case COURSE -> {
				requireNoExtras(spec, true, false);
				requireCourse(spec.courseId());
				yield students(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(spec.courseId()));
			}
			case SELECTED_STUDENTS -> selectedStudents(spec);
			case ALL_TEACHERS -> {
				requireNoExtras(spec, false, false);
				yield teacherLookupApi.listApprovedActiveTeachers()
					.stream()
					.map(teacher -> new Recipient(teacher.userId(), teacher.name(), RecipientKind.TEACHER,
							teacher.email() != null, false, false))
					.toList();
			}
			case PAYMENT_STATUS -> paymentStatusStudents(spec);
		};
		if (recipients.size() > MAX_RECIPIENTS) {
			throw invalid("audience", "matches " + recipients.size() + " recipients - the limit is "
					+ MAX_RECIPIENTS + " per broadcast");
		}
		return recipients.stream().sorted(Comparator.comparing(r -> (r.name() == null) ? "" : r.name())).toList();
	}

	private List<Recipient> selectedStudents(AudienceSpec spec) {
		if (spec.courseId() != null || spec.paymentStatus() != null) {
			throw invalid("audience", "selected students take no course or payment status");
		}
		if (spec.studentIds() == null || spec.studentIds().isEmpty()) {
			throw invalid("audience.studentIds", "must contain at least one student");
		}
		Set<UUID> distinct = new LinkedHashSet<>(spec.studentIds());
		if (distinct.size() > MAX_RECIPIENTS) {
			throw invalid("audience.studentIds", "must contain at most " + MAX_RECIPIENTS + " students");
		}
		// studentIds are student profile ids - the id the staff student list exposes.
		List<StudentContact> contacts = studentLookupApi.findContactsByStudentIds(distinct);
		if (contacts.size() != distinct.size()) {
			// A foreign or non-student id: reject the whole request, never send to a subset.
			throw invalid("audience.studentIds", "contains ids that are not students of this institute");
		}
		return contacts.stream().filter(AudienceResolver::isActive).map(AudienceResolver::toRecipient).toList();
	}

	private List<Recipient> paymentStatusStudents(AudienceSpec spec) {
		if (spec.paymentStatus() == null) {
			throw invalid("audience.paymentStatus", "is required");
		}
		if (spec.studentIds() != null && !spec.studentIds().isEmpty()) {
			throw invalid("audience.studentIds", "is not used with a payment status audience");
		}
		List<UUID> orderIds;
		if (spec.courseId() != null) {
			requireCourse(spec.courseId());
			orderIds = paymentStatusApi.findOrderIdsForCourse(spec.courseId());
		}
		else {
			orderIds = paymentStatusApi.findAllOrderIdsForCurrentTenant();
		}
		Set<UUID> studentIds = new LinkedHashSet<>();
		if (!orderIds.isEmpty()) {
			for (OrderPaymentDetail detail : paymentStatusApi.findOrderPaymentDetails(orderIds)) {
				if (matches(detail, spec.paymentStatus())) {
					studentIds.add(detail.studentId());
				}
			}
		}
		return students(studentIds);
	}

	/**
	 * Per order: PAID = a confirmed payment exists; PENDING = an open attempt
	 * and nothing confirmed; REJECTED = only rejected attempts. A student
	 * matches when any of their in-scope orders matches.
	 */
	static boolean matches(OrderPaymentDetail detail, PaymentStatusFilter filter) {
		boolean confirmed = detail.confirmedPaymentId() != null;
		return switch (filter) {
			case PAID -> confirmed;
			case PENDING -> !confirmed && detail.hasPendingPayment();
			case REJECTED -> !confirmed && !detail.hasPendingPayment() && detail.hasRejectedPayment();
		};
	}

	private List<Recipient> students(java.util.Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		List<Recipient> recipients = new ArrayList<>();
		for (StudentContact contact : studentLookupApi.findContacts(new LinkedHashSet<>(userIds))) {
			if (isActive(contact)) {
				recipients.add(toRecipient(contact));
			}
		}
		return recipients;
	}

	private void requireCourse(UUID courseId) {
		if (courseId == null) {
			throw invalid("audience.courseId", "is required");
		}
		if (courseLookupApi.getCourseSummaries(Set.of(courseId)).isEmpty()) {
			throw new NotFoundException("Course not found");
		}
	}

	private static void requireNoExtras(AudienceSpec spec, boolean courseAllowed, boolean statusAllowed) {
		if ((!courseAllowed && spec.courseId() != null) || (!statusAllowed && spec.paymentStatus() != null)
				|| (spec.studentIds() != null && !spec.studentIds().isEmpty())) {
			throw invalid("audience", "has fields that do not apply to " + spec.type());
		}
	}

	private static boolean isActive(StudentContact contact) {
		return "active".equalsIgnoreCase(contact.accountStatus());
	}

	private static Recipient toRecipient(StudentContact contact) {
		boolean mobile = contact.mobile() != null && !contact.mobile().isBlank();
		boolean whatsApp = (contact.whatsappNumber() != null && !contact.whatsappNumber().isBlank()) || mobile;
		return new Recipient(contact.userId(), contact.name(), RecipientKind.STUDENT, contact.email() != null, mobile,
				whatsApp);
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("Invalid audience", List.of(new FieldError(field, message)));
	}

}
