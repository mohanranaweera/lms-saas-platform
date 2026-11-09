package com.lms.tutemanagement.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.api.Capability;
import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.repository.TuteRepository;
import com.lms.tutemanagement.service.TuteInputValidator.TuteInput;
import com.lms.tutemanagement.service.TuteInputValidator.ValidTute;
import com.lms.tutemanagement.service.TuteViews.DeleteResultView;
import com.lms.tutemanagement.service.TuteViews.PaidSessionView;
import com.lms.tutemanagement.service.TuteViews.TuteView;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-01 section 3: "Manage Tutes" of a course - list, paid-session options, create, edit
 * (optimistic version, quantity never below allocated), delete (hard delete without history,
 * else archive). Every entry point re-checks the exact capability and course scope through
 * {@link TuteAccessGuard}; the tenant is always the resolved context; every change is audited
 * in the same transaction.
 */
@Service
@Transactional
public class TuteService {

	static final int DEFAULT_PAGE_SIZE = 10;

	static final int MAX_PAGE_SIZE = 100;

	private final TuteRepository tuteRepository;

	private final TuteFulfillmentRepository fulfillmentRepository;

	private final TuteAccessGuard accessGuard;

	private final TuteStock stock;

	private final TuteSupport support;

	private final CourseLookupApi courseLookupApi;

	private final Clock clock;

	public TuteService(TuteRepository tuteRepository, TuteFulfillmentRepository fulfillmentRepository,
			TuteAccessGuard accessGuard, TuteStock stock, TuteSupport support, CourseLookupApi courseLookupApi,
			Clock clock) {
		this.tuteRepository = tuteRepository;
		this.fulfillmentRepository = fulfillmentRepository;
		this.accessGuard = accessGuard;
		this.stock = stock;
		this.support = support;
		this.courseLookupApi = courseLookupApi;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<TuteView> list(UUID courseId, String search, boolean includeArchived, int page, int size) {
		accessGuard.requireCourse(courseId, Capability.TUTE_VIEW_TUTES);
		int safePage = Math.max(0, page);
		int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		Page<Tute> tutes = tuteRepository.search(courseId, search, includeArchived,
				PageRequest.of(safePage, safeSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
		Map<UUID, long[]> counts = fulfillmentRepository
			.countAllocatedByTute(tutes.getContent().stream().map(Tute::getId).toList());
		Map<UUID, String> lessonTitles = lessonTitles(courseId, tutes.getContent());
		List<TuteView> content = tutes.getContent()
			.stream()
			.map(tute -> view(tute, counts.get(tute.getId()),
					tute.getPaidLessonId() == null ? null : lessonTitles.get(tute.getPaidLessonId())))
			.toList();
		return new PageResponse<>(content, safePage, safeSize, tutes.getTotalElements(), tutes.getTotalPages());
	}

	@Transactional(readOnly = true)
	public List<PaidSessionView> paidSessions(UUID courseId, String search) {
		accessGuard.requireCourse(courseId, Capability.TUTE_VIEW_TUTES);
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		String currency = support.courseCurrency(courseId);
		return courseLookupApi.findLessonPolicies(courseId)
			.stream()
			.filter(lesson -> lesson.paymentType() == LessonPaymentType.PAID)
			.filter(lesson -> needle.isEmpty() || contains(lesson.title(), needle) || contains(lesson.lessonLabel(), needle)
					|| contains(lesson.moduleTitle(), needle))
			.sorted(Comparator.comparingInt(LessonPolicy::moduleSequence).thenComparingInt(LessonPolicy::sequence))
			.map(lesson -> new PaidSessionView(lesson.lessonId(), lesson.title(), lesson.lessonLabel(),
					lesson.moduleTitle(), lesson.price(), currency, lesson.startsAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public TuteView get(UUID courseId, UUID tuteId) {
		accessGuard.requireCourse(courseId, Capability.TUTE_VIEW_TUTE);
		return view(load(courseId, tuteId));
	}

	public TuteView create(UUID courseId, TuteInput input) {
		accessGuard.requireCourse(courseId, Capability.TUTE_CREATE);
		ValidTute valid = TuteInputValidator.validate(input);
		requireEligibleSession(courseId, valid.paidLessonId());
		Tute tute = tuteRepository.saveAndFlush(new Tute(support.tenantId(), courseId, valid.name(),
				valid.description(), valid.quantity(), valid.paymentType(), valid.price(),
				support.courseCurrency(courseId), valid.paidLessonId()));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", courseId.toString());
		metadata.put("name", tute.getName());
		metadata.put("quantity", tute.getQuantity());
		metadata.put("paymentType", tute.getPaymentType().name());
		putIfPresent(metadata, "price", tute.getPrice() == null ? null : tute.getPrice().toPlainString());
		putIfPresent(metadata, "currency", tute.getCurrency());
		putIfPresent(metadata, "paidLessonId", tute.getPaidLessonId() == null ? null : tute.getPaidLessonId().toString());
		support.audit(TuteAccessGuard.actorId(), "tute.created", "tute", tute.getId(), null, metadata);
		return view(tute);
	}

	public TuteView update(UUID courseId, UUID tuteId, TuteInput input, Long expectedVersion) {
		accessGuard.requireCourse(courseId, Capability.TUTE_UPDATE);
		if (expectedVersion == null) {
			throw new FieldValidationException("The tute is not valid",
					List.of(new FieldError("version", "Version is required")));
		}
		ValidTute valid = TuteInputValidator.validate(input);
		Tute tute = load(courseId, tuteId);
		stock.lock(tute);
		if (tute.getVersion() != expectedVersion) {
			throw TuteException.versionConflict();
		}
		if (tute.isArchived()) {
			throw TuteException.archived();
		}
		if (!Objects.equals(valid.paidLessonId(), tute.getPaidLessonId()) || valid.paidLessonId() != null) {
			requireEligibleSession(courseId, valid.paidLessonId());
		}
		long allocated = fulfillmentRepository.countAllocated(tute.getId());
		if (valid.quantity() < allocated) {
			throw TuteException.quantityBelowAllocated(allocated);
		}
		Map<String, Object> changes = new LinkedHashMap<>();
		change(changes, "name", tute.getName(), valid.name());
		change(changes, "description", tute.getDescription(), valid.description());
		change(changes, "quantity", tute.getQuantity(), valid.quantity());
		change(changes, "paymentType", tute.getPaymentType().name(), valid.paymentType().name());
		change(changes, "price", tute.getPrice() == null ? null : tute.getPrice().toPlainString(),
				valid.price() == null ? null : valid.price().toPlainString());
		change(changes, "paidLessonId", tute.getPaidLessonId() == null ? null : tute.getPaidLessonId().toString(),
				valid.paidLessonId() == null ? null : valid.paidLessonId().toString());
		if (changes.isEmpty()) {
			return view(tute);
		}
		tute.update(valid.name(), valid.description(), valid.quantity(), valid.paymentType(), valid.price(),
				valid.paidLessonId());
		tuteRepository.saveAndFlush(tute);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", courseId.toString());
		metadata.put("changes", changes);
		support.audit(TuteAccessGuard.actorId(), "tute.updated", "tute", tute.getId(), null, metadata);
		return view(tute);
	}

	public DeleteResultView delete(UUID courseId, UUID tuteId) {
		accessGuard.requireCourse(courseId, Capability.TUTE_DELETE);
		Tute tute = load(courseId, tuteId);
		stock.lock(tute);
		UUID actorId = TuteAccessGuard.actorId();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", courseId.toString());
		metadata.put("name", tute.getName());
		if (!fulfillmentRepository.existsByTuteId(tute.getId())) {
			tuteRepository.delete(tute);
			tuteRepository.flush();
			support.audit(actorId, "tute.deleted", "tute", tute.getId(), null, metadata);
			return new DeleteResultView("DELETED");
		}
		if (!tute.isArchived()) {
			tute.archive(actorId, clock.instant());
			tuteRepository.saveAndFlush(tute);
			support.audit(actorId, "tute.archived", "tute", tute.getId(), null, metadata);
		}
		return new DeleteResultView("ARCHIVED");
	}

	// ------------------------------------------------------------------

	Tute load(UUID courseId, UUID tuteId) {
		return tuteRepository.findByIdAndCourseId(tuteId, courseId)
			.orElseThrow(() -> new NotFoundException("Tute not found"));
	}

	TuteView view(Tute tute) {
		Map<UUID, long[]> counts = fulfillmentRepository.countAllocatedByTute(List.of(tute.getId()));
		String lessonTitle = tute.getPaidLessonId() == null ? null
				: lessonTitles(tute.getCourseId(), List.of(tute)).get(tute.getPaidLessonId());
		return view(tute, counts.get(tute.getId()), lessonTitle);
	}

	private static TuteView view(Tute tute, long[] counts, String lessonTitle) {
		long reserved = counts == null ? 0 : counts[0];
		long dispatched = counts == null ? 0 : counts[1];
		return new TuteView(tute.getId(), tute.getCourseId(), tute.getName(), tute.getDescription(),
				tute.getQuantity(), reserved, dispatched, Math.max(0, tute.getQuantity() - reserved - dispatched),
				tute.getPaymentType().name(), tute.getPrice(), tute.getCurrency(), tute.getPaidLessonId(), lessonTitle,
				tute.getStatus().name(), tute.getArchivedAt(), tute.getVersion(), tute.getCreatedAt(),
				tute.getUpdatedAt());
	}

	private Map<UUID, String> lessonTitles(UUID courseId, List<Tute> tutes) {
		Set<UUID> lessonIds = tutes.stream()
			.map(Tute::getPaidLessonId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (lessonIds.isEmpty()) {
			return Map.of();
		}
		return courseLookupApi.findLessonPolicies(courseId)
			.stream()
			.filter(lesson -> lessonIds.contains(lesson.lessonId()))
			.collect(Collectors.toMap(LessonPolicy::lessonId,
					lesson -> lesson.title() == null ? "" : lesson.title(), (a, b) -> a));
	}

	/**
	 * The linked lesson must be a PAID session of THIS course in the caller's tenant; an unknown,
	 * cross-course or cross-tenant id looks identical (400, never a 404 that confirms existence).
	 */
	private void requireEligibleSession(UUID courseId, UUID lessonId) {
		if (lessonId == null) {
			return;
		}
		boolean eligible = courseLookupApi.findLessonPolicies(courseId)
			.stream()
			.anyMatch(lesson -> lessonId.equals(lesson.lessonId()) && lesson.paymentType() == LessonPaymentType.PAID);
		if (!eligible) {
			throw TuteInputValidator.sessionNotEligible("Choose a paid session of this course");
		}
	}

	private static void change(Map<String, Object> changes, String field, Object before, Object after) {
		if (!Objects.equals(before, after)) {
			Map<String, Object> diff = new LinkedHashMap<>();
			diff.put("from", before);
			diff.put("to", after);
			changes.put(field, diff);
		}
	}

	private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
		if (value != null) {
			metadata.put(key, value);
		}
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

}
