package com.lms.tutemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi;
import com.lms.identityaccessservice.api.Capability;
import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.repository.TuteFulfillmentWrites.NewFulfillment;
import com.lms.tutemanagement.service.TuteViews.AssignResultView;
import com.lms.tutemanagement.service.TuteViews.EligibleStudentView;
import com.lms.tutemanagement.service.TuteViews.FulfillmentView;
import com.lms.tutemanagement.service.TuteViews.SyncResultView;
import com.lms.usermanagement.api.StudentDeliveryContact;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-01 sections 3-5: a tute's assigned students - list, eligible-student search, manual
 * assignment (stock reserved under the tute row lock; all-or-nothing on out of stock), unroll
 * (cancel a PENDING copy, reason required), status actions (hand over, change method, release
 * hold) and "Sync payments". Exact capability + course scope re-checked on every call; tenant
 * from the resolved context only; every change audited in the same transaction (never with
 * addresses or phones).
 */
@Service
@Transactional
public class TuteFulfillmentService {

	public static final int MAX_STUDENT_IDS = 200;

	static final int MAX_ROWS = 10_000;

	static final int MAX_PARTICIPANTS = 10_000;

	static final int MAX_ELIGIBLE_RESULTS = 50;

	static final int REASON_MAX = 500;

	private final TuteService tuteService;

	private final TuteFulfillmentRepository fulfillmentRepository;

	private final TuteAccessGuard accessGuard;

	private final TuteStock stock;

	private final TuteSupport support;

	private final TutePaymentAssignmentService paymentAssignmentService;

	private final StudentLookupApi studentLookupApi;

	private final UnpaidStudentsEnrollmentApi participantsApi;

	private final ContentCoverageApi contentCoverageApi;

	private final Clock clock;

	public TuteFulfillmentService(TuteService tuteService, TuteFulfillmentRepository fulfillmentRepository,
			TuteAccessGuard accessGuard, TuteStock stock, TuteSupport support,
			TutePaymentAssignmentService paymentAssignmentService, StudentLookupApi studentLookupApi,
			UnpaidStudentsEnrollmentApi participantsApi, ContentCoverageApi contentCoverageApi, Clock clock) {
		this.tuteService = tuteService;
		this.fulfillmentRepository = fulfillmentRepository;
		this.accessGuard = accessGuard;
		this.stock = stock;
		this.support = support;
		this.paymentAssignmentService = paymentAssignmentService;
		this.studentLookupApi = studentLookupApi;
		this.participantsApi = participantsApi;
		this.contentCoverageApi = contentCoverageApi;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public PageResponse<FulfillmentView> list(UUID courseId, UUID tuteId, String status, String search, int page,
			int size) {
		accessGuard.requireCourse(courseId, Capability.TUTE_VIEW_TUTE);
		Tute tute = tuteService.load(courseId, tuteId);
		FulfillmentStatus statusFilter = parseStatus(status);
		List<TuteFulfillment> rows = fulfillmentRepository.findAllByTuteId(tute.getId())
			.stream()
			.filter(row -> statusFilter == null || row.getStatus() == statusFilter)
			.limit(MAX_ROWS)
			.toList();
		Map<UUID, StudentDeliveryContact> contacts = contacts(rows.stream().map(TuteFulfillment::getStudentId).toList());
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		List<FulfillmentView> all = rows.stream()
			.map(row -> view(row, contacts.get(row.getStudentId())))
			.filter(view -> needle.isEmpty() || contains(view.studentName(), needle)
					|| contains(view.indexNumber(), needle))
			.toList();
		int safePage = Math.max(0, page);
		int safeSize = size < 1 ? TuteService.DEFAULT_PAGE_SIZE : Math.min(size, TuteService.MAX_PAGE_SIZE);
		int from = (int) Math.min((long) safePage * safeSize, all.size());
		int to = Math.min(from + safeSize, all.size());
		int totalPages = all.isEmpty() ? 0 : (all.size() + safeSize - 1) / safeSize;
		return new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size(), totalPages);
	}

	/** Course participants matching {@code search} (name / index number), at most 50, name-ordered. */
	@Transactional(readOnly = true)
	public List<EligibleStudentView> eligibleStudents(UUID courseId, UUID tuteId, String search) {
		accessGuard.requireCourse(courseId, Capability.TUTE_ENROLL);
		Tute tute = tuteService.load(courseId, tuteId);
		Set<UUID> assigned = new HashSet<>(fulfillmentRepository.findActiveStudentIds(tute.getId()));
		Set<UUID> participants = new LinkedHashSet<>();
		participantsApi.findCurrentCourseParticipants(courseId, MAX_PARTICIPANTS)
			.forEach(fact -> participants.add(fact.studentId()));
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		return studentLookupApi.findDeliveryContacts(participants)
			.stream()
			.filter(contact -> needle.isEmpty() || contains(contact.name(), needle)
					|| contains(contact.indexNumber(), needle))
			.sorted(Comparator.comparing((StudentDeliveryContact c) -> c.name() == null ? "" : c.name(),
					String.CASE_INSENSITIVE_ORDER).thenComparing(StudentDeliveryContact::userId))
			.limit(MAX_ELIGIBLE_RESULTS)
			.map(contact -> new EligibleStudentView(contact.userId(), contact.studentProfileId(),
					contact.indexNumber(), contact.name(), assigned.contains(contact.userId())))
			.toList();
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	/**
	 * Manual assignment of course participants. Stock is checked once for the whole request
	 * under the tute row lock: if the new students exceed {@code available}, nothing is written
	 * (409 {@code TUTE_OUT_OF_STOCK}). Students who already hold a copy count as already assigned.
	 */
	public AssignResultView assign(UUID courseId, UUID tuteId, List<UUID> studentIds, String method) {
		accessGuard.requireCourse(courseId, Capability.TUTE_ENROLL);
		Tute tute = tuteService.load(courseId, tuteId);
		if (studentIds == null || studentIds.isEmpty()) {
			throw invalid("studentIds", "Choose at least one student");
		}
		if (studentIds.size() > MAX_STUDENT_IDS) {
			throw invalid("studentIds", "Assign at most " + MAX_STUDENT_IDS + " students at a time");
		}
		FulfillmentMethod fulfillmentMethod = method == null || method.isBlank() ? FulfillmentMethod.POSTAL
				: parseMethod(method);
		LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
		for (UUID studentId : studentIds) {
			if (studentId == null) {
				throw invalid("studentIds", "Every student must be chosen from the course's students");
			}
			distinct.add(studentId);
		}
		for (UUID studentId : distinct) {
			if (!contentCoverageApi.isParticipant(studentId, courseId)) {
				throw invalid("studentIds", "Every student must be enrolled in the tute's course");
			}
		}
		stock.lock(tute);
		if (tute.isArchived()) {
			throw TuteException.archived();
		}
		Set<UUID> assigned = new HashSet<>(fulfillmentRepository.findActiveStudentIds(tute.getId()));
		List<UUID> toAdd = distinct.stream().filter(id -> !assigned.contains(id)).toList();
		long available = stock.available(tute);
		if (toAdd.size() > available) {
			throw TuteException.outOfStock(available, toAdd.size());
		}
		UUID actorId = TuteAccessGuard.actorId();
		Instant now = clock.instant();
		List<String> added = new ArrayList<>();
		for (UUID studentId : toAdd) {
			if (fulfillmentRepository.insertIfAbsent(new NewFulfillment(tute.getId(), courseId, studentId,
					fulfillmentMethod, FulfillmentSource.MANUAL, null, null, tute.getName(), tute.getPaymentType(),
					tute.getPrice(), tute.getCurrency(), null, null, null, null, actorId, now)) == 1) {
				added.add(studentId.toString());
			}
		}
		int already = distinct.size() - added.size();
		if (!added.isEmpty()) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("courseId", courseId.toString());
			metadata.put("studentIds", added);
			metadata.put("method", fulfillmentMethod.name());
			metadata.put("alreadyAssigned", already);
			support.audit(actorId, "tute.students_assigned", "tute", tute.getId(), null, metadata);
		}
		return new AssignResultView(added.size(), already, stock.available(tute));
	}

	/** Unroll: cancels a PENDING copy (releases stock). Terminal rows are never changed. */
	public FulfillmentView cancel(UUID courseId, UUID tuteId, UUID fulfillmentId, String reason) {
		accessGuard.requireCourse(courseId, Capability.TUTE_UNROLL);
		String cleanReason = reason == null ? "" : reason.strip();
		if (cleanReason.isEmpty() || cleanReason.length() > REASON_MAX) {
			throw invalid("reason", "Give a reason (at most " + REASON_MAX + " characters)");
		}
		TuteFulfillment row = lockRow(courseId, tuteId, fulfillmentId);
		if (row.getStatus() != FulfillmentStatus.PENDING) {
			throw TuteException.invalidState("Only a pending tute can be unrolled");
		}
		UUID actorId = TuteAccessGuard.actorId();
		row.cancel(actorId, clock.instant(), cleanReason);
		fulfillmentRepository.saveAndFlush(row);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("fulfillmentId", row.getId().toString());
		metadata.put("studentId", row.getStudentId().toString());
		metadata.put("source", row.getSource().name());
		support.audit(actorId, "tute.fulfillment_cancelled", "tute", row.getTuteId(), cleanReason, metadata);
		return view(row, contacts(List.of(row.getStudentId())).get(row.getStudentId()));
	}

	/** {@code MARK_HANDED_OVER} | {@code CHANGE_METHOD} (+ {@code method}) | {@code RELEASE_HOLD}. */
	public FulfillmentView updateStatus(UUID courseId, UUID tuteId, UUID fulfillmentId, String action,
			String method, String reason) {
		accessGuard.requireCourse(courseId, Capability.TUTE_UPDATE_STATUS);
		String safeAction = action == null ? "" : action.strip();
		if (!List.of("MARK_HANDED_OVER", "CHANGE_METHOD", "RELEASE_HOLD").contains(safeAction)) {
			throw invalid("action", "Choose MARK_HANDED_OVER, CHANGE_METHOD or RELEASE_HOLD");
		}
		FulfillmentMethod newMethod = "CHANGE_METHOD".equals(safeAction) ? parseMethod(method) : null;
		String releaseReason = null;
		if ("RELEASE_HOLD".equals(safeAction)) {
			releaseReason = reason == null ? "" : reason.strip();
			if (releaseReason.isEmpty() || releaseReason.length() > REASON_MAX) {
				throw invalid("reason", "Give a reason for releasing the hold (at most " + REASON_MAX + " characters)");
			}
		}
		TuteFulfillment row = lockRow(courseId, tuteId, fulfillmentId);
		if (row.getStatus() != FulfillmentStatus.PENDING) {
			throw TuteException.invalidState("This tute is already " + row.getStatus().name().toLowerCase(Locale.ROOT));
		}
		UUID actorId = TuteAccessGuard.actorId();
		Instant now = clock.instant();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("fulfillmentId", row.getId().toString());
		metadata.put("studentId", row.getStudentId().toString());
		String auditAction;
		switch (safeAction) {
			case "MARK_HANDED_OVER" -> {
				if (row.getMethod() != FulfillmentMethod.COLLECTION) {
					throw TuteException.invalidState("Postal tutes are dispatched from Tute Dispatch");
				}
				if (row.isHeld()) {
					throw TuteException.invalidState("This tute is on hold because its payment was reversed");
				}
				row.dispatch(actorId, now, null, null, null, null, null, null, null);
				auditAction = "tute.handed_over";
			}
			case "CHANGE_METHOD" -> {
				if (row.getMethod() == newMethod) {
					return view(row, contacts(List.of(row.getStudentId())).get(row.getStudentId()));
				}
				metadata.put("from", row.getMethod().name());
				metadata.put("to", newMethod.name());
				row.changeMethod(newMethod);
				auditAction = "tute.method_changed";
			}
			default -> {
				if (!row.isHeld()) {
					throw TuteException.invalidState("This tute is not on hold");
				}
				row.releaseHold(actorId, now);
				auditAction = "tute.hold_released";
			}
		}
		fulfillmentRepository.saveAndFlush(row);
		support.audit(actorId, auditAction, "tute", row.getTuteId(), releaseReason, metadata);
		return view(row, contacts(List.of(row.getStudentId())).get(row.getStudentId()));
	}

	/** "Sync payments" (spec section 5 entry point 2): the same matcher over confirmed payments. */
	public SyncResultView syncPayments(UUID courseId, UUID tuteId) {
		accessGuard.requireCourse(courseId, Capability.TUTE_UPDATE);
		Tute tute = tuteService.load(courseId, tuteId);
		if (tute.isArchived()) {
			throw TuteException.archived();
		}
		UUID actorId = TuteAccessGuard.actorId();
		SyncResultView result = paymentAssignmentService.syncTute(tute, actorId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", courseId.toString());
		metadata.put("added", result.added());
		metadata.put("alreadyAssigned", result.alreadyAssigned());
		metadata.put("skippedOutOfStock", result.skippedOutOfStock());
		metadata.put("held", result.held());
		support.audit(actorId, "tute.payments_synced", "tute", tute.getId(), null, metadata);
		return result;
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private TuteFulfillment lockRow(UUID courseId, UUID tuteId, UUID fulfillmentId) {
		Tute tute = tuteService.load(courseId, tuteId);
		fulfillmentRepository.lockFulfillments(List.of(fulfillmentId));
		return fulfillmentRepository.findById(fulfillmentId)
			.filter(row -> row.getTuteId().equals(tute.getId()))
			.orElseThrow(() -> new NotFoundException("Tute assignment not found"));
	}

	Map<UUID, StudentDeliveryContact> contacts(List<UUID> studentIds) {
		return studentLookupApi.findDeliveryContacts(studentIds)
			.stream()
			.collect(Collectors.toMap(StudentDeliveryContact::userId, Function.identity(), (a, b) -> a));
	}

	static FulfillmentView view(TuteFulfillment row, StudentDeliveryContact contact) {
		DeliveryAddress address = row.getStatus() == FulfillmentStatus.DISPATCHED && row.getDispatchAddressSource() != null
				? new DeliveryAddress(row.getDispatchRecipientName(), row.getDispatchAddressLine(),
						row.getDispatchAddressLine2(), row.getDispatchCity(), row.getDispatchPostalCode(),
						row.getDispatchPhone(), row.getDispatchAddressSource())
				: DeliveryAddress.resolve(row, contact);
		return new FulfillmentView(row.getId(), row.getTuteId(), row.getCourseId(), row.getStudentId(),
				contact == null ? null : contact.studentProfileId(), contact == null ? null : contact.indexNumber(),
				contact == null ? null : contact.name(), address.city(), address.missing(), row.getStatus().name(),
				row.getMethod().name(), row.getSource().name(), row.getTuteName(), row.getPaymentType().name(),
				row.getPrice(), row.getCurrency(), row.isHeld(), row.getPaymentReversedAt(), row.getHoldReleasedAt(),
				row.getCreatedAt(), row.getDispatchedAt(), row.getCancelledAt(), row.getCancelReason());
	}

	private static FulfillmentStatus parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return FulfillmentStatus.valueOf(status.strip());
		}
		catch (IllegalArgumentException ex) {
			throw invalid("status", "Choose PENDING, DISPATCHED or CANCELLED");
		}
	}

	private static FulfillmentMethod parseMethod(String method) {
		if (method == null) {
			throw invalid("method", "Choose POSTAL or COLLECTION");
		}
		try {
			return FulfillmentMethod.valueOf(method.strip());
		}
		catch (IllegalArgumentException ex) {
			throw invalid("method", "Choose POSTAL or COLLECTION");
		}
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("The request is not valid", List.of(new FieldError(field, message)));
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

}
