package com.lms.tutemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.Capability;
import com.lms.tutemanagement.api.TuteDispatchLabelApi;
import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.repository.TuteRepository;
import com.lms.tutemanagement.service.DispatchProjection.Group;
import com.lms.tutemanagement.service.DispatchProjection.SortKey;
import com.lms.tutemanagement.service.TuteViews.ConfirmDispatchView;
import com.lms.tutemanagement.service.TuteViews.DispatchItemView;
import com.lms.tutemanagement.service.TuteViews.DispatchQueueView;
import com.lms.tutemanagement.service.TuteViews.DispatchRowView;
import com.lms.tutemanagement.service.TuteViews.FilterOptionsView;
import com.lms.tutemanagement.service.TuteViews.OptionView;
import com.lms.tutemanagement.service.TuteViews.TuteOptionView;
import com.lms.usermanagement.api.StudentDeliveryContact;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-01 section 7 Tute Dispatch: queue rows / counts, filter options, labels (via {@link
 * TuteDispatchLabelApi}) and dispatch confirmation, all from ONE projection ({@link
 * DispatchProjection}) over the tenant's PENDING, POSTAL, not-held copies (Teacher: own courses
 * only). The working set is capped at {@value #MAX_WORKING_SET} copies (422 {@code
 * TUTE_WORKING_SET_TOO_LARGE} above it). Labels never mutate anything; confirmation revalidates
 * every selected copy under row locks and transitions each exactly once (all or nothing).
 */
@Service
@Transactional
public class TuteDispatchService implements TuteDispatchLabelApi {

	public static final int MAX_WORKING_SET = 10_000;

	static final int DEFAULT_PAGE_SIZE = 25;

	static final String DASH = " – ";

	private final TuteFulfillmentRepository fulfillmentRepository;

	private final TuteRepository tuteRepository;

	private final TuteAccessGuard accessGuard;

	private final TuteSupport support;

	private final StudentLookupApi studentLookupApi;

	private final CourseLookupApi courseLookupApi;

	private final Clock clock;

	public TuteDispatchService(TuteFulfillmentRepository fulfillmentRepository, TuteRepository tuteRepository,
			TuteAccessGuard accessGuard, TuteSupport support, StudentLookupApi studentLookupApi,
			CourseLookupApi courseLookupApi, Clock clock) {
		this.fulfillmentRepository = fulfillmentRepository;
		this.tuteRepository = tuteRepository;
		this.accessGuard = accessGuard;
		this.support = support;
		this.studentLookupApi = studentLookupApi;
		this.courseLookupApi = courseLookupApi;
		this.clock = clock;
	}

	/** Display names of the tutes and courses referenced by a set of rows. */
	private record Names(Map<UUID, String> tutes, Map<UUID, String> courses) {

		String tute(TuteFulfillment row) {
			return tutes.getOrDefault(row.getTuteId(), row.getTuteName());
		}

		String course(TuteFulfillment row) {
			return courses.getOrDefault(row.getCourseId(), "");
		}

		String label(TuteFulfillment row) {
			String course = course(row);
			return course.isBlank() ? tute(row) : course + DASH + tute(row);
		}

	}

	// ------------------------------------------------------------------
	// Queue.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public DispatchQueueView queue(DispatchFilter filter, String sort, String direction, int page, int size) {
		Set<UUID> scope = accessGuard.requireScope(Capability.TUTE_DISPATCH_VIEW);
		SortKey sortKey = DispatchProjection.parseSort(sort);
		boolean descending = DispatchProjection.parseDescending(direction);
		List<TuteFulfillment> rows = workingSet(scope);
		Names names = names(rows);
		List<Group> groups = DispatchProjection
			.sort(DispatchProjection.filter(DispatchProjection.group(rows, contacts(rows)), filter), sortKey, descending);
		int safePage = Math.max(0, page);
		int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, TuteService.MAX_PAGE_SIZE);
		int from = (int) Math.min((long) safePage * safeSize, groups.size());
		int to = Math.min(from + safeSize, groups.size());
		List<DispatchRowView> content = groups.subList(from, to).stream().map(group -> row(group, names)).toList();
		long missing = groups.stream().filter(group -> group.address().missing()).count();
		long items = groups.stream().mapToLong(group -> group.items().size()).sum();
		int totalPages = groups.isEmpty() ? 0 : (groups.size() + safeSize - 1) / safeSize;
		return new DispatchQueueView(content, safePage, safeSize, groups.size(), totalPages, missing, items);
	}

	@Transactional(readOnly = true)
	public FilterOptionsView filterOptions() {
		Set<UUID> scope = accessGuard.requireScope(Capability.TUTE_DISPATCH_VIEW);
		List<TuteFulfillment> rows = workingSet(scope);
		Names names = names(rows);
		Map<UUID, OptionView> courses = new LinkedHashMap<>();
		Map<UUID, TuteOptionView> tutes = new LinkedHashMap<>();
		for (TuteFulfillment row : rows) {
			courses.putIfAbsent(row.getCourseId(), new OptionView(row.getCourseId(), names.course(row)));
			tutes.putIfAbsent(row.getTuteId(), new TuteOptionView(row.getTuteId(), names.tute(row), row.getCourseId()));
		}
		return new FilterOptionsView(
				courses.values().stream().sorted(Comparator.comparing(OptionView::name, String.CASE_INSENSITIVE_ORDER)).toList(),
				tutes.values()
					.stream()
					.sorted(Comparator.comparing(TuteOptionView::name, String.CASE_INSENSITIVE_ORDER))
					.toList());
	}

	// ------------------------------------------------------------------
	// Labels (read-only).
	// ------------------------------------------------------------------

	@Override
	@Transactional(readOnly = false)
	public List<DispatchLabel> prepareLabels(LabelRequest request) {
		Set<UUID> scope = accessGuard.requireScope(Capability.TUTE_DISPATCH_PRINT_LABELS);
		if (request == null) {
			throw invalid("fulfillmentIds", "Choose the tutes to print");
		}
		List<Group> groups;
		List<TuteFulfillment> rows;
		String mode;
		if (request.allFiltered()) {
			if (request.expectedLabelCount() == null || request.expectedLabelCount() < 0) {
				throw invalid("expectedLabelCount", "Confirm how many labels you expect to print");
			}
			rows = workingSet(scope);
			groups = DispatchProjection.filter(DispatchProjection.group(rows, contacts(rows)), request.filters());
			if (groups.size() != request.expectedLabelCount()) {
				throw TuteException.stale("The tutes to send changed since the list was loaded. Reload and try again.",
						null, Map.of("expectedLabelCount", request.expectedLabelCount(), "labelCount", groups.size()));
			}
			mode = "ALL_FILTERED";
		}
		else {
			List<UUID> ids = requireIds(request.fulfillmentIds());
			rows = loadInScope(ids, scope);
			List<UUID> stale = rows.stream().filter(row -> !dispatchable(row)).map(TuteFulfillment::getId).toList();
			if (!stale.isEmpty()) {
				throw TuteException.stale("Some selected tutes are no longer waiting to be sent. Reload and try again.",
						stale, null);
			}
			groups = DispatchProjection.group(rows, contacts(rows));
			mode = "SELECTED";
		}
		if (groups.isEmpty()) {
			throw TuteException.stale("There are no tutes to print", null, null);
		}
		groups = DispatchProjection.sort(groups, SortKey.INDEX_NUMBER, false);
		Names names = names(rows);
		List<DispatchLabel> labels = groups.stream().map(group -> label(group, names)).toList();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("mode", mode);
		metadata.put("labelCount", labels.size());
		metadata.put("tuteCount", labels.stream().mapToInt(DispatchLabel::tuteCount).sum());
		metadata.put("missingAddressCount", labels.stream().filter(DispatchLabel::addressMissing).count());
		support.audit(TuteAccessGuard.actorId(), "tute.dispatch_labels_printed", "tute_dispatch", support.tenantId(),
				null, metadata);
		return labels;
	}

	// ------------------------------------------------------------------
	// Confirm dispatch.
	// ------------------------------------------------------------------

	/**
	 * Revalidates every selected copy (tenant, scope, PENDING, POSTAL, not held, address present)
	 * under row locks and dispatches all of them, or none: unknown ids 404, foreign-course ids
	 * (Teacher) 403, ineligible ids 409 {@code TUTE_SELECTION_STALE}, missing addresses 422 {@code
	 * TUTE_ADDRESS_MISSING}. Already-dispatched copies are reported, never re-applied. The
	 * resolved address is snapshotted onto each dispatched row.
	 */
	public ConfirmDispatchView confirm(List<UUID> fulfillmentIds) {
		Set<UUID> scope = accessGuard.requireScope(Capability.TUTE_UPDATE_STATUS);
		List<UUID> ids = requireIds(fulfillmentIds);
		List<UUID> locked = fulfillmentRepository.lockFulfillments(ids);
		if (locked.size() != ids.size()) {
			throw new NotFoundException("Some selected tutes were not found");
		}
		List<TuteFulfillment> rows = loadInScope(ids, scope);
		List<TuteFulfillment> already = rows.stream()
			.filter(row -> row.getStatus() == FulfillmentStatus.DISPATCHED)
			.toList();
		List<UUID> stale = rows.stream()
			.filter(row -> row.getStatus() != FulfillmentStatus.DISPATCHED && !dispatchable(row))
			.map(TuteFulfillment::getId)
			.toList();
		if (!stale.isEmpty()) {
			throw TuteException.stale("Some selected tutes can no longer be dispatched. Reload and try again.", stale,
					null);
		}
		List<TuteFulfillment> pending = rows.stream().filter(row -> row.getStatus() == FulfillmentStatus.PENDING).toList();
		Map<UUID, StudentDeliveryContact> contacts = contacts(pending);
		Map<UUID, DeliveryAddress> addresses = new HashMap<>();
		List<UUID> missing = new ArrayList<>();
		for (TuteFulfillment row : pending) {
			DeliveryAddress address = DeliveryAddress.resolve(row, contacts.get(row.getStudentId()));
			addresses.put(row.getId(), address);
			if (address.missing()) {
				missing.add(row.getId());
			}
		}
		if (!missing.isEmpty()) {
			throw TuteException.addressMissing(missing);
		}
		UUID actorId = TuteAccessGuard.actorId();
		Instant now = clock.instant();
		Map<UUID, List<String>> dispatchedByTute = new LinkedHashMap<>();
		for (TuteFulfillment row : pending) {
			DeliveryAddress address = addresses.get(row.getId());
			StudentDeliveryContact contact = contacts.get(row.getStudentId());
			String name = address.recipientName() != null ? address.recipientName()
					: contact == null ? null : contact.name();
			row.dispatch(actorId, now, truncate(name, 255), truncate(address.addressLine(), 255),
					truncate(address.addressLine2(), 255), truncate(address.city(), 100),
					truncate(address.postalCode(), 20), truncate(address.phone(), 50), address.source());
			fulfillmentRepository.save(row);
			dispatchedByTute.computeIfAbsent(row.getTuteId(), id -> new ArrayList<>()).add(row.getId().toString());
		}
		fulfillmentRepository.flush();
		dispatchedByTute.forEach((tuteId, dispatchedIds) -> {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("fulfillmentIds", dispatchedIds);
			metadata.put("count", dispatchedIds.size());
			support.audit(actorId, "tute.dispatched", "tute", tuteId, null, metadata);
		});
		return new ConfirmDispatchView(pending.size(), already.size(),
				pending.stream().map(TuteFulfillment::getId).toList(),
				already.stream().map(TuteFulfillment::getId).toList());
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private List<TuteFulfillment> workingSet(Set<UUID> scope) {
		long count = fulfillmentRepository.countDispatchable(scope);
		if (count > MAX_WORKING_SET) {
			throw TuteException.workingSetTooLarge(count, MAX_WORKING_SET);
		}
		return fulfillmentRepository.findDispatchable(scope);
	}

	/** Tenant-scoped load of exactly {@code ids}: unknown / cross-tenant 404, foreign course 403. */
	private List<TuteFulfillment> loadInScope(List<UUID> ids, Set<UUID> scope) {
		Map<UUID, TuteFulfillment> byId = fulfillmentRepository.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(TuteFulfillment::getId, Function.identity()));
		if (byId.size() != ids.size()) {
			throw new NotFoundException("Some selected tutes were not found");
		}
		List<TuteFulfillment> rows = ids.stream().map(byId::get).toList();
		if (rows.stream().anyMatch(row -> !TuteAccessGuard.inScope(scope, row.getCourseId()))) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		return rows.stream()
			.sorted(Comparator.comparing(TuteFulfillment::getCreatedAt).thenComparing(TuteFulfillment::getId))
			.toList();
	}

	private static boolean dispatchable(TuteFulfillment row) {
		return row.getStatus() == FulfillmentStatus.PENDING && row.getMethod() == FulfillmentMethod.POSTAL
				&& !row.isHeld();
	}

	private static List<UUID> requireIds(List<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			throw invalid("fulfillmentIds", "Choose at least one tute");
		}
		if (ids.size() > MAX_FULFILLMENT_IDS) {
			throw invalid("fulfillmentIds", "Choose at most " + MAX_FULFILLMENT_IDS + " tutes at a time");
		}
		if (ids.stream().anyMatch(java.util.Objects::isNull)) {
			throw invalid("fulfillmentIds", "Every selected tute must be valid");
		}
		return List.copyOf(new LinkedHashSet<>(ids));
	}

	private Map<UUID, StudentDeliveryContact> contacts(List<TuteFulfillment> rows) {
		return studentLookupApi
			.findDeliveryContacts(rows.stream().map(TuteFulfillment::getStudentId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(StudentDeliveryContact::userId, Function.identity(), (a, b) -> a));
	}

	private Names names(List<TuteFulfillment> rows) {
		Set<UUID> tuteIds = rows.stream().map(TuteFulfillment::getTuteId).collect(Collectors.toSet());
		Set<UUID> courseIds = rows.stream().map(TuteFulfillment::getCourseId).collect(Collectors.toSet());
		Map<UUID, String> tutes = tuteIds.isEmpty() ? Map.of()
				: tuteRepository.findAllById(tuteIds).stream().collect(Collectors.toMap(Tute::getId, Tute::getName));
		Map<UUID, String> courses = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(courseIds)
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, summary -> summary.name() == null ? "" : summary.name(),
							(a, b) -> a));
		return new Names(tutes, courses);
	}

	private static DispatchRowView row(Group group, Names names) {
		List<DispatchItemView> items = group.items()
			.stream()
			.map(item -> new DispatchItemView(item.getId(), item.getTuteId(), names.tute(item), item.getCourseId(),
					names.course(item), names.label(item)))
			.toList();
		StudentDeliveryContact contact = group.contact();
		return new DispatchRowView(group.groupKey(), group.studentId(),
				contact == null ? null : contact.studentProfileId(), group.indexNumber(), group.studentName(),
				group.city(), group.address().missing(), items.size(), items);
	}

	private static DispatchLabel label(Group group, Names names) {
		String name = group.studentName() == null ? "" : group.studentName();
		return new DispatchLabel(name, group.address().missing() ? List.of() : group.address().lines(),
				group.address().missing(), group.phone(), group.items().stream().map(names::label).toList(),
				group.items().size());
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("The request is not valid", List.of(new FieldError(field, message)));
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

}
