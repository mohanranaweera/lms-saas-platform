package com.lms.enrollmentmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.LessonMaterialSummary;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.LessonResourceTypeCatalog;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.enrollmentmanagement.repository.LessonProgressRepository;
import com.lms.enrollmentmanagement.service.CourseContentViews.AccessView;
import com.lms.enrollmentmanagement.service.CourseContentViews.CourseContent;
import com.lms.enrollmentmanagement.service.CourseContentViews.LaunchResource;
import com.lms.enrollmentmanagement.service.CourseContentViews.LessonLaunch;
import com.lms.enrollmentmanagement.service.CourseContentViews.LessonNode;
import com.lms.enrollmentmanagement.service.CourseContentViews.LiveClassNode;
import com.lms.enrollmentmanagement.service.CourseContentViews.ModuleNode;
import com.lms.enrollmentmanagement.service.CourseContentViews.PeriodNode;
import com.lms.enrollmentmanagement.service.CourseContentViews.PeriodPayment;
import com.lms.enrollmentmanagement.service.CourseContentViews.PeriodPurchase;
import com.lms.enrollmentmanagement.service.CourseContentViews.PlayerLinks;
import com.lms.enrollmentmanagement.service.LessonAccessService.CourseLessonAccess;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student course content (KLASS-T4b, plan §4.3, CM-S02..S06, EP-L01..L06):
 * the content tree (periods → modules → lessons, including linked source
 * courses' modules, D13), the live-class schedule and the lesson launch.
 * Every per-lesson answer comes from the single {@link LessonAccessService}
 * evaluator; nothing here ever returns a resource URL.
 *
 * <p>Viewed progress (D14) is recorded ONLY by a successful, authorized
 * {@link #launch}; reading the tree records nothing.
 *
 * <p>Access to the tree: a participating student (direct, legacy enrollment,
 * or via a parent course linking this one), or staff preview - the owning
 * teacher or a {@code COURSES}/{@code VIEW} grant - always within the caller's
 * own tenant (unknown/cross-tenant/DRAFT-for-students course = 404).
 */
@Service
public class CourseContentService {

	private static final String STUDENT_ROLE = "STUDENT";

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private static final String NO_PERIOD_LABEL = "Course content";

	private final LessonAccessService lessonAccessService;

	private final CourseLookupApi courseLookupApi;

	private final LessonProgressRepository progressRepository;

	private final MaterialLookupApi materialLookupApi;

	private final ClassSessionLookupApi classSessionLookupApi;

	private final VideoAccessApi videoAccessApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	public CourseContentService(LessonAccessService lessonAccessService, CourseLookupApi courseLookupApi,
			LessonProgressRepository progressRepository, MaterialLookupApi materialLookupApi,
			ClassSessionLookupApi classSessionLookupApi, VideoAccessApi videoAccessApi,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext,
			PermissionCheckService permissionCheckService) {
		this.lessonAccessService = lessonAccessService;
		this.courseLookupApi = courseLookupApi;
		this.progressRepository = progressRepository;
		this.materialLookupApi = materialLookupApi;
		this.classSessionLookupApi = classSessionLookupApi;
		this.videoAccessApi = videoAccessApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
	}

	/** One lesson with the course it was evaluated in and its decision. */
	record EvaluatedLesson(UUID courseId, boolean source, boolean freePriced, LessonPolicy lesson,
			LessonAccessDecision decision) {

	}

	@Transactional
	public CourseContent getContent(UUID courseId) {
		List<EvaluatedLesson> lessons = loadTree(courseId);
		Set<UUID> viewed = viewedLessonIds(lessons);
		List<PeriodNode> periods = buildPeriods(lessons, viewed, isStudent());
		return new CourseContent(courseId, (int) lessons.stream().filter(l -> viewed.contains(l.lesson().lessonId())).count(),
				lessons.size(), tenantTimezone(), periods);
	}

	@Transactional
	public List<LiveClassNode> liveClasses(UUID courseId) {
		List<EvaluatedLesson> lessons = loadTree(courseId);
		Set<UUID> viewed = viewedLessonIds(lessons);
		Instant now = Instant.now();
		return lessons.stream()
			.filter(l -> LessonResourceTypeCatalog.isLiveType(l.lesson().resourceType()))
			.filter(l -> isUpcoming(l.lesson(), now))
			.sorted(Comparator.comparing((EvaluatedLesson l) -> l.lesson().startsAt(),
					Comparator.nullsLast(Comparator.naturalOrder())))
			.map(l -> {
				LessonNode node = toNode(l, viewed.contains(l.lesson().lessonId()));
				return new LiveClassNode(node.lessonId(), node.courseId(), node.moduleId(), l.lesson().moduleTitle(),
						node.title(), node.lessonLabel(), node.resourceType(), node.paymentType(), node.price(),
						node.startsAt(), node.endsAt(), node.viewed(), node.access());
			})
			.toList();
	}

	/**
	 * Student-only. ALLOWED → records the first view (idempotent) and lists
	 * the lesson's student-visible materials and class sessions (ids/titles
	 * only - each is then opened through its own lesson-guarded endpoint).
	 * UNAVAILABLE → 404; any other status → 403 {@code {code, access}}.
	 */
	@Transactional
	public LessonLaunch launch(UUID courseId, UUID lessonId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		LessonAccessDecision decision = lessonAccessService.evaluate(courseId, List.of(lessonId)).get(lessonId);
		if (decision.status() == LessonAccessStatus.UNAVAILABLE) {
			throw new NotFoundException("Lesson not found");
		}
		if (decision.status() == LessonAccessStatus.NOT_ENROLLED && !isPublicCourse(courseId)) {
			// D22: a non-participant of a non-PUBLIC course gets the same 404
			// as the content tree - never a 403 that confirms the lesson exists.
			throw new NotFoundException("Lesson not found");
		}
		if (!decision.allowed()) {
			throw new LessonAccessDeniedException(decision);
		}
		progressRepository.recordFirstView(principal.userId(), courseId, lessonId, Instant.now());

		List<LaunchResource> resources = new ArrayList<>();
		List<LessonMaterialSummary> materials = materialLookupApi.findStudentVisibleLessonMaterials(lessonId);
		for (LessonMaterialSummary material : materials) {
			resources.add(new LaunchResource("MATERIAL", material.materialId(), material.title(),
					material.materialType()));
		}
		for (ClassSessionSummary session : classSessionLookupApi.findByLessonId(lessonId)) {
			if (!ClassSessionSummary.STATUS_CANCELLED.equals(session.status())) {
				resources.add(new LaunchResource("CLASS_SESSION", session.id(), session.title(), session.status()));
			}
		}
		boolean hasVideo = materials.stream().anyMatch(LessonMaterialSummary::isVideo);
		boolean handoff = hasVideo && videoAccessApi.isNativePlayerRequired();
		return new LessonLaunch(AccessView.of(decision), resources, handoff, playerLinks());
	}

	private boolean isPublicCourse(UUID courseId) {
		return courseLookupApi.findCatalogInfo(courseId).map(info -> "PUBLIC".equals(info.status())).orElse(false);
	}

	// ------------------------------------------------------------------
	// Tree loading.
	// ------------------------------------------------------------------

	private List<EvaluatedLesson> loadTree(UUID courseId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		CourseCatalogInfo course = courseLookupApi.findCatalogInfo(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		List<UUID> sourceIds = courseLookupApi.findLinkedSourceCourseIds(courseId);
		List<EvaluatedLesson> lessons = new ArrayList<>();
		if (STUDENT_ROLE.equals(principal.role())) {
			if ("DRAFT".equals(course.status()) || !lessonAccessService.isCallerParticipant(courseId)) {
				throw new NotFoundException("Course not found");
			}
			addEvaluated(lessons, lessonAccessService.evaluateAllForCaller(courseId), false);
			for (UUID sourceId : sourceIds) {
				// The SOURCE course's own rules/entitlements (D13); enrolled via the link.
				addEvaluated(lessons, lessonAccessService.evaluateAllForCaller(sourceId), true);
			}
			return lessons;
		}
		requireStaffPreview(principal, course);
		addPreview(lessons, courseId, courseLookupApi.findLessonPolicies(courseId), false);
		for (UUID sourceId : sourceIds) {
			if (mayPreviewLinkedSource(principal, sourceId)) {
				addPreview(lessons, sourceId, courseLookupApi.findLessonPolicies(sourceId), true);
			}
		}
		return lessons;
	}

	/**
	 * KLASS-T8b SEC L5: a Teacher / Teacher Assistant previewing their own
	 * course sees a linked SOURCE course's lessons only when they also own the
	 * source course or hold {@code COURSES}/{@code VIEW}; staff who passed the
	 * matrix check (Tenant Admin, Coordinator, ...) are unchanged.
	 */
	private boolean mayPreviewLinkedSource(AuthenticatedPrincipal principal, UUID sourceId) {
		if (!TEACHER_ROLE.equals(principal.role()) && !TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			return true;
		}
		boolean ownsSource = courseLookupApi.findCatalogInfo(sourceId)
			.map(source -> principal.userId().equals(source.teacherId()))
			.orElse(false);
		return ownsSource || permissionCheckService.hasPermission(DomainArea.COURSES, PermissionAction.VIEW);
	}

	private static void addEvaluated(List<EvaluatedLesson> target, CourseLessonAccess access, boolean source) {
		boolean freePriced = access.course().map(info -> "FREE".equals(info.pricingModel())).orElse(false);
		for (LessonPolicy lesson : access.lessons()) {
			target.add(new EvaluatedLesson(access.courseId(), source, freePriced, lesson,
					access.decisions().get(lesson.lessonId())));
		}
	}

	/** Staff preview: metadata is ALLOWED; payment shows the lesson's own policy (FREE or COVERED). */
	private static void addPreview(List<EvaluatedLesson> target, UUID courseId, List<LessonPolicy> lessons,
			boolean source) {
		Instant now = Instant.now();
		for (LessonPolicy lesson : lessons) {
			LessonAccessDecision decision = new LessonAccessDecision(lesson.lessonId(), courseId,
					LessonAccessStatus.ALLOWED, LessonAccessRules.availability(lesson, now),
					lesson.paymentType() == LessonPaymentType.FREE ? LessonAccessDecision.Payment.FREE
							: LessonAccessDecision.Payment.COVERED,
					null);
			target.add(new EvaluatedLesson(courseId, source, false, lesson, decision));
		}
	}

	private void requireStaffPreview(AuthenticatedPrincipal principal, CourseCatalogInfo course) {
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			if (!principal.userId().equals(course.teacherId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.VIEW);
	}

	private Set<UUID> viewedLessonIds(List<EvaluatedLesson> lessons) {
		if (!isStudent() || lessons.isEmpty()) {
			return Set.of();
		}
		Set<UUID> ids = new HashSet<>();
		lessons.forEach(l -> ids.add(l.lesson().lessonId()));
		return progressRepository.findViewedLessonIds(AuthenticatedPrincipalHolder.get().userId(), ids);
	}

	private static boolean isStudent() {
		return STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role());
	}

	private static boolean isUpcoming(LessonPolicy lesson, Instant now) {
		if (lesson.endsAt() != null) {
			return !lesson.endsAt().isBefore(now);
		}
		return lesson.startsAt() != null && !lesson.startsAt().isBefore(now);
	}

	// ------------------------------------------------------------------
	// Periods.
	// ------------------------------------------------------------------

	private record PeriodKey(int year, int month) {

	}

	/**
	 * Groups lessons by their explicit billing month, newest first; lessons with
	 * no billing month form the trailing {@code key = null} group. Inside a
	 * period, modules keep course order (the parent course first, then linked
	 * source courses in link order), then module sequence; lessons keep their
	 * sequence.
	 */
	static List<PeriodNode> buildPeriods(List<EvaluatedLesson> lessons, Set<UUID> viewed, boolean studentView) {
		TreeMap<PeriodKey, List<EvaluatedLesson>> byPeriod = new TreeMap<>(
				Comparator.comparingInt(PeriodKey::year).thenComparingInt(PeriodKey::month).reversed());
		List<EvaluatedLesson> noPeriod = new ArrayList<>();
		for (EvaluatedLesson lesson : lessons) {
			if (lesson.lesson().billingYear() != null && lesson.lesson().billingMonth() != null) {
				byPeriod.computeIfAbsent(new PeriodKey(lesson.lesson().billingYear(), lesson.lesson().billingMonth()),
						key -> new ArrayList<>()).add(lesson);
			}
			else {
				noPeriod.add(lesson);
			}
		}
		List<PeriodNode> periods = new ArrayList<>();
		byPeriod.forEach((key, group) -> periods.add(new PeriodNode(
				String.format(Locale.ROOT, "%04d-%02d", key.year(), key.month()), key.year(), key.month(),
				Month.of(key.month()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + key.year(),
				periodPayment(group, key, studentView), modules(group, viewed))));
		if (!noPeriod.isEmpty()) {
			periods.add(new PeriodNode(null, null, null, NO_PERIOD_LABEL, periodPayment(noPeriod, null, studentView),
					modules(noPeriod, viewed)));
		}
		return periods;
	}

	private static List<ModuleNode> modules(List<EvaluatedLesson> group, Set<UUID> viewed) {
		Map<UUID, List<EvaluatedLesson>> byModule = new LinkedHashMap<>();
		for (EvaluatedLesson lesson : group) {
			byModule.computeIfAbsent(lesson.lesson().moduleId(), key -> new ArrayList<>()).add(lesson);
		}
		List<ModuleNode> modules = new ArrayList<>();
		byModule.forEach((moduleId, moduleLessons) -> {
			EvaluatedLesson first = moduleLessons.get(0);
			modules.add(new ModuleNode(moduleId, first.lesson().moduleTitle(), first.lesson().moduleSequence(),
					first.source() ? first.courseId() : null,
					moduleLessons.stream().map(l -> toNode(l, viewed.contains(l.lesson().lessonId()))).toList()));
		});
		return modules;
	}

	/**
	 * Over the period's COVERED_BY_CLASS_FEES lessons: none (or all FREE-priced)
	 * → NOT_REQUIRED; all covered → PAID; every uncovered one pending → PENDING;
	 * some covered or pending → PARTIAL; else REQUIRED. The purchase is the
	 * MONTHLY item of the first course with an uncovered, non-pending lesson.
	 */
	private static PeriodPayment periodPayment(List<EvaluatedLesson> group, PeriodKey key, boolean studentView) {
		List<EvaluatedLesson> covered = group.stream()
			.filter(l -> l.lesson().paymentType() == LessonPaymentType.COVERED_BY_CLASS_FEES)
			.toList();
		if (!studentView || covered.isEmpty()) {
			return new PeriodPayment("NOT_REQUIRED", null);
		}
		long paid = covered.stream().filter(l -> l.decision().payment() == LessonAccessDecision.Payment.COVERED).count();
		long pending = covered.stream()
			.filter(l -> l.decision().payment() == LessonAccessDecision.Payment.PENDING)
			.count();
		if (paid == covered.size()) {
			return new PeriodPayment(allFreePricedCourse(covered) ? "NOT_REQUIRED" : "PAID", null);
		}
		PeriodPurchase purchase = key == null ? null
				: covered.stream()
					.filter(l -> l.decision().payment() == LessonAccessDecision.Payment.REQUIRED)
					.map(l -> new PeriodPurchase("MONTHLY", l.courseId(), key.year(), key.month()))
					.findFirst()
					.orElse(null);
		if (paid + pending == covered.size()) {
			return new PeriodPayment(paid == 0 ? "PENDING" : "PARTIAL", null);
		}
		return new PeriodPayment(paid > 0 || pending > 0 ? "PARTIAL" : "REQUIRED", purchase);
	}

	/** COVERED lessons of a FREE-priced course carry no requiredPurchase and are covered by pricing alone. */
	private static boolean allFreePricedCourse(List<EvaluatedLesson> covered) {
		return covered.stream().allMatch(EvaluatedLesson::freePriced);
	}

	private static LessonNode toNode(EvaluatedLesson l, boolean viewed) {
		LessonPolicy lesson = l.lesson();
		return new LessonNode(lesson.lessonId(), l.courseId(), lesson.moduleId(), lesson.title(),
				lesson.lessonLabel(), lesson.resourceType() == null ? null : lesson.resourceType().name(),
				lesson.paymentType() == null ? null : lesson.paymentType().name(),
				lesson.paymentType() == LessonPaymentType.PAID ? lesson.price() : null, lesson.startsAt(),
				lesson.endsAt(), viewed, AccessView.of(l.decision()));
	}

	// ------------------------------------------------------------------
	// Tenant config.
	// ------------------------------------------------------------------

	private String tenantTimezone() {
		try {
			Object configured = tenantConfigApi
				.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zone) {
				return ZoneId.of(zone).getId();
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// fall through to UTC
		}
		return "UTC";
	}

	private PlayerLinks playerLinks() {
		Map<String, Object> video = tenantConfigApi.resolveDomain(tenantContext.getTenantId(), ConfigDomain.VIDEO);
		return new PlayerLinks(asString(video.get("player_android_url")), asString(video.get("player_ios_url")));
	}

	private static String asString(Object value) {
		return value instanceof String s && !s.isBlank() ? s : null;
	}

}
