package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamLeaderboard;
import com.lms.exammanagement.domain.ExamLeaderboardExam;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamLeaderboardExamRepository;
import com.lms.exammanagement.repository.ExamLeaderboardRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.ExamParticipationReader.StudentFacts;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardDetailView;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardExamView;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardRanksView;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardSummaryView;
import com.lms.exammanagement.service.exam.ExamResultsViews.PerExamMarksView;
import com.lms.exammanagement.service.exam.ExamResultsViews.RankExamView;
import com.lms.exammanagement.service.exam.ExamResultsViews.RankRowView;
import com.lms.exammanagement.service.exam.ExamResultsViews.StudentLeaderboardView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass exam leaderboards (contract "Slice E", D7).
 *
 * <p>Staff / teacher CRUD: a name (1-120) and at least two distinct live Klass exams of the
 * tenant. Unknown / other-tenant exam ids are 404. Scope ({@link
 * ExamAccessGuard#resolveLeaderboardScope}): staff with {@code DomainArea.EXAMS} see and group
 * every exam (VIEW to read, CREATE_EDIT to create / change / delete - deleting removes only the
 * grouping); a Teacher only exams of their own courses (another course's exam 403, a
 * leaderboard containing one 403); Teacher Assistants read only. Every write is audited with
 * before / after.
 *
 * <p>Ranks (D7): Σ marks of the included exams whose results are published ({@code
 * results_published_at}); a student is listed when enrolled in at least one included published
 * exam; an enrolled published exam without marks counts 0; ties share the rank (1, 1, 3); {@code
 * maxPossible} = Σ total marks of the included published exams the student was enrolled in;
 * {@code share} = total / maxPossible × 100 (1 decimal, {@code null} when maxPossible is 0).
 * Unpublished exams are listed with {@code published:false} and contribute nothing - their marks
 * never appear. No rows while no published exam has marks ("Ranks will appear once marks are
 * entered").
 *
 * <p>Students: only {@code published} leaderboards that include a live exam they are enrolled in
 * (else 404); all ranked rows are shown, but other students' rows carry no id, index number or
 * per-exam marks.
 */
@Service
@Transactional
public class ExamLeaderboardService {

	static final int MIN_EXAMS = 2;

	static final int DEFAULT_PAGE_SIZE = 20;

	static final int MAX_PAGE_SIZE = 100;

	private static final String STUDENT_ROLE = "STUDENT";

	private final ExamLeaderboardRepository leaderboardRepository;

	private final ExamLeaderboardExamRepository memberRepository;

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamParticipationReader participationReader;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final ExamAccessGuard accessGuard;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public ExamLeaderboardService(ExamLeaderboardRepository leaderboardRepository,
			ExamLeaderboardExamRepository memberRepository, ExamRepository examRepository,
			ExamEnrollmentRepository enrollmentRepository, ExamParticipationReader participationReader,
			CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi, ExamAccessGuard accessGuard,
			AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.leaderboardRepository = leaderboardRepository;
		this.memberRepository = memberRepository;
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.participationReader = participationReader;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.accessGuard = accessGuard;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Staff / teacher.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<LeaderboardSummaryView> list(String search) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.VIEW);
		List<ExamLeaderboard> leaderboards = leaderboardRepository.findAllOrdered();
		Map<UUID, List<Exam>> examsByBoard = liveExamsByBoard(
				leaderboards.stream().map(ExamLeaderboard::getId).collect(Collectors.toSet()));
		String needle = needle(search);
		List<LeaderboardSummaryView> views = new ArrayList<>();
		for (ExamLeaderboard leaderboard : leaderboards) {
			List<Exam> exams = examsByBoard.getOrDefault(leaderboard.getId(), List.of());
			if (!visible(exams, scope)) {
				continue;
			}
			if (!needle.isEmpty() && !leaderboard.getName().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			views.add(new LeaderboardSummaryView(leaderboard.getId(), leaderboard.getName(), exams.size(),
					leaderboard.isPublished()));
		}
		return views;
	}

	@Transactional(readOnly = true)
	public LeaderboardDetailView get(UUID leaderboardId) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.VIEW);
		ExamLeaderboard leaderboard = load(leaderboardId);
		List<Exam> exams = liveExams(leaderboard.getId());
		requireVisible(exams, scope);
		return detail(leaderboard, exams);
	}

	public LeaderboardDetailView create(String name, List<UUID> examIds, Boolean published) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.CREATE_EDIT);
		String validName = validateName(name);
		List<Exam> exams = validateExams(examIds, scope);
		UUID tenantId = tenantContext.getTenantId();
		ExamLeaderboard leaderboard = leaderboardRepository
			.saveAndFlush(new ExamLeaderboard(tenantId, validName, Boolean.TRUE.equals(published)));
		for (Exam exam : exams) {
			memberRepository.save(new ExamLeaderboardExam(tenantId, leaderboard.getId(), exam.getId()));
		}
		memberRepository.flush();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("after", snapshot(leaderboard, exams));
		audit("exam_leaderboard.created", leaderboard.getId(), metadata);
		return detail(leaderboard, exams);
	}

	public LeaderboardDetailView update(UUID leaderboardId, String name, List<UUID> examIds, Boolean published) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.CREATE_EDIT);
		ExamLeaderboard leaderboard = load(leaderboardId);
		List<Exam> current = liveExams(leaderboard.getId());
		requireVisible(current, scope);
		String validName = validateName(name);
		List<Exam> exams = validateExams(examIds, scope);
		Map<String, Object> before = snapshot(leaderboard, current);

		Set<UUID> wanted = exams.stream().map(Exam::getId).collect(Collectors.toSet());
		List<ExamLeaderboardExam> members = memberRepository.findAllByLeaderboardId(leaderboard.getId());
		Set<UUID> kept = new LinkedHashSet<>();
		for (ExamLeaderboardExam member : members) {
			if (wanted.contains(member.getExamId())) {
				kept.add(member.getExamId());
			}
			else {
				memberRepository.delete(member);
			}
		}
		UUID tenantId = tenantContext.getTenantId();
		for (Exam exam : exams) {
			if (!kept.contains(exam.getId())) {
				memberRepository.save(new ExamLeaderboardExam(tenantId, leaderboard.getId(), exam.getId()));
			}
		}
		leaderboard.update(validName, published == null ? leaderboard.isPublished() : published);
		leaderboardRepository.saveAndFlush(leaderboard);
		memberRepository.flush();

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", before);
		metadata.put("after", snapshot(leaderboard, exams));
		audit("exam_leaderboard.updated", leaderboard.getId(), metadata);
		return detail(leaderboard, exams);
	}

	/** Hard delete of the grouping only (memberships + the leaderboard row); no exam data changes. */
	public void delete(UUID leaderboardId) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.CREATE_EDIT);
		ExamLeaderboard leaderboard = load(leaderboardId);
		List<Exam> current = liveExams(leaderboard.getId());
		requireVisible(current, scope);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", snapshot(leaderboard, current));
		memberRepository.findAllByLeaderboardId(leaderboard.getId()).forEach(memberRepository::delete);
		memberRepository.flush();
		leaderboardRepository.delete(leaderboard);
		leaderboardRepository.flush();
		audit("exam_leaderboard.deleted", leaderboardId, metadata);
	}

	@Transactional(readOnly = true)
	public LeaderboardRanksView ranks(UUID leaderboardId, String search, int page, int size) {
		Set<UUID> scope = accessGuard.resolveLeaderboardScope(PermissionAction.VIEW);
		ExamLeaderboard leaderboard = load(leaderboardId);
		List<Exam> exams = liveExams(leaderboard.getId());
		requireVisible(exams, scope);
		return ranks(leaderboard, exams, search, page, size, null);
	}

	// ------------------------------------------------------------------
	// Students.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<StudentLeaderboardView> studentList() {
		UUID studentId = requireStudent();
		Set<UUID> enrolledExamIds = enrolledLiveExamIds(studentId);
		Set<UUID> boardIds = memberRepository.findAllByExamIdIn(enrolledExamIds)
			.stream()
			.map(ExamLeaderboardExam::getLeaderboardId)
			.collect(Collectors.toSet());
		List<ExamLeaderboard> leaderboards = leaderboardRepository.findPublishedByIdIn(boardIds);
		Map<UUID, List<Exam>> examsByBoard = liveExamsByBoard(
				leaderboards.stream().map(ExamLeaderboard::getId).collect(Collectors.toSet()));
		return leaderboards.stream()
			.map(leaderboard -> new StudentLeaderboardView(leaderboard.getId(), leaderboard.getName(),
					examsByBoard.getOrDefault(leaderboard.getId(), List.of()).size()))
			.toList();
	}

	@Transactional(readOnly = true)
	public LeaderboardRanksView studentRanks(UUID leaderboardId, String search, int page, int size) {
		UUID studentId = requireStudent();
		ExamLeaderboard leaderboard = leaderboardRepository.findById(leaderboardId)
			.filter(ExamLeaderboard::isPublished)
			.orElseThrow(ExamLeaderboardService::notFound);
		List<Exam> exams = liveExams(leaderboard.getId());
		Set<UUID> examIds = exams.stream().map(Exam::getId).collect(Collectors.toSet());
		boolean enrolled = enrollmentRepository.findAllByStudentId(studentId)
			.stream()
			.anyMatch(enrollment -> examIds.contains(enrollment.getExamId()));
		if (!enrolled) {
			throw notFound();
		}
		return ranks(leaderboard, exams, search, page, size, studentId);
	}

	// ------------------------------------------------------------------
	// Ranking (D7).
	// ------------------------------------------------------------------

	/** {@code viewer} = the student viewing (masks other students' identity / per-exam marks), or null for staff. */
	private LeaderboardRanksView ranks(ExamLeaderboard leaderboard, List<Exam> exams, String search, int page,
			int size, UUID viewer) {
		page = Math.max(page, 0);
		size = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		List<RankExamView> examViews = exams.stream()
			.map(exam -> new RankExamView(exam.getId(), exam.getTitle(), exam.getTotalMarks(),
					exam.getResultsPublishedAt() != null))
			.toList();
		List<Exam> publishedExams = exams.stream().filter(exam -> exam.getResultsPublishedAt() != null).toList();
		Map<UUID, List<StudentFacts>> facts = participationReader.studentFacts(publishedExams);

		// student -> (exam -> marks or 0 when enrolled without marks)
		Map<UUID, Map<UUID, BigDecimal>> marksByStudent = new HashMap<>();
		boolean anyMarks = false;
		for (Exam exam : publishedExams) {
			for (StudentFacts student : facts.getOrDefault(exam.getId(), List.of())) {
				anyMarks |= student.graded();
				marksByStudent.computeIfAbsent(student.studentId(), id -> new HashMap<>())
					.put(exam.getId(), student.graded() ? student.marks() : BigDecimal.ZERO);
			}
		}
		if (!anyMarks) {
			return new LeaderboardRanksView(leaderboard.getId(), leaderboard.getName(), leaderboard.isPublished(),
					examViews, List.of(), page, size, 0, 0, false);
		}

		Map<UUID, StudentIndexEntry> students = new HashMap<>();
		studentLookupApi.findStudentIndexEntries(marksByStudent.keySet())
			.forEach(entry -> students.put(entry.userId(), entry));

		List<Ranked> ranked = new ArrayList<>();
		for (Map.Entry<UUID, Map<UUID, BigDecimal>> entry : marksByStudent.entrySet()) {
			BigDecimal total = BigDecimal.ZERO;
			BigDecimal maxPossible = BigDecimal.ZERO;
			for (Exam exam : publishedExams) {
				BigDecimal marks = entry.getValue().get(exam.getId());
				if (marks != null) {
					total = total.add(marks);
					maxPossible = maxPossible.add(exam.getTotalMarks() == null ? BigDecimal.ZERO : exam.getTotalMarks());
				}
			}
			StudentIndexEntry student = students.get(entry.getKey());
			ranked.add(new Ranked(entry.getKey(), student == null ? null : student.indexNumber(),
					student == null || student.name() == null ? "" : student.name(), total, maxPossible,
					entry.getValue()));
		}
		ranked.sort(Comparator.comparing(Ranked::total, Comparator.reverseOrder())
			.thenComparing(row -> row.name().toLowerCase(Locale.ROOT))
			.thenComparing(row -> row.indexNumber() == null ? "￿" : row.indexNumber())
			.thenComparing(Ranked::studentId));

		List<RankRowView> rows = new ArrayList<>(ranked.size());
		int rank = 0;
		BigDecimal previous = null;
		for (int i = 0; i < ranked.size(); i++) {
			Ranked row = ranked.get(i);
			if (previous == null || row.total().compareTo(previous) != 0) {
				rank = i + 1;
				previous = row.total();
			}
			boolean self = row.studentId().equals(viewer);
			boolean reveal = viewer == null || self;
			List<PerExamMarksView> perExam = !reveal ? List.of()
					: exams.stream().map(exam -> new PerExamMarksView(exam.getId(), row.marks().get(exam.getId()))).toList();
			BigDecimal share = row.maxPossible().signum() == 0 ? null
					: row.total().multiply(BigDecimal.valueOf(100)).divide(row.maxPossible(), 1, RoundingMode.HALF_UP);
			rows.add(new RankRowView(rank, reveal ? row.studentId() : null, reveal ? row.indexNumber() : null,
					row.name(), row.total(), row.maxPossible(), share, perExam, self));
		}

		String needle = needle(search);
		List<RankRowView> filtered = needle.isEmpty() ? rows
				: rows.stream()
					.filter(row -> row.name().toLowerCase(Locale.ROOT).contains(needle)
							|| (row.indexNumber() != null && row.indexNumber().toLowerCase(Locale.ROOT).contains(needle)))
					.toList();
		int from = (int) Math.min((long) page * size, filtered.size());
		int to = Math.min(from + size, filtered.size());
		int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + size - 1) / size;
		return new LeaderboardRanksView(leaderboard.getId(), leaderboard.getName(), leaderboard.isPublished(),
				examViews, filtered.subList(from, to), page, size, filtered.size(), totalPages, true);
	}

	private record Ranked(UUID studentId, String indexNumber, String name, BigDecimal total, BigDecimal maxPossible,
			Map<UUID, BigDecimal> marks) {
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private ExamLeaderboard load(UUID leaderboardId) {
		return leaderboardRepository.findById(leaderboardId).orElseThrow(ExamLeaderboardService::notFound);
	}

	/** Live Klass exams of one leaderboard, in schedule order. */
	private List<Exam> liveExams(UUID leaderboardId) {
		return liveExamsByBoard(Set.of(leaderboardId)).getOrDefault(leaderboardId, List.of());
	}

	private Map<UUID, List<Exam>> liveExamsByBoard(Set<UUID> leaderboardIds) {
		List<ExamLeaderboardExam> members = memberRepository.findAllByLeaderboardIdIn(leaderboardIds);
		Map<UUID, Exam> exams = new HashMap<>();
		examRepository.findLiveKlassExamsByIds(members.stream().map(ExamLeaderboardExam::getExamId).collect(Collectors.toSet()))
			.forEach(exam -> exams.put(exam.getId(), exam));
		Map<UUID, List<Exam>> result = new HashMap<>();
		for (ExamLeaderboardExam member : members) {
			Exam exam = exams.get(member.getExamId());
			if (exam != null) {
				result.computeIfAbsent(member.getLeaderboardId(), id -> new ArrayList<>()).add(exam);
			}
		}
		result.values().forEach(list -> list.sort(EXAM_ORDER));
		return result;
	}

	private static final Comparator<Exam> EXAM_ORDER = Comparator.comparing(Exam::getScheduledStart)
		.thenComparing(Exam::getTitle, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(Exam::getId);

	/** A teacher sees a leaderboard only when every live exam in it belongs to their courses. */
	private static boolean visible(List<Exam> exams, Set<UUID> scope) {
		return scope == null || (!exams.isEmpty() && exams.stream().allMatch(exam -> scope.contains(exam.getCourseId())));
	}

	private static void requireVisible(List<Exam> exams, Set<UUID> scope) {
		if (!visible(exams, scope)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

	private Set<UUID> enrolledLiveExamIds(UUID studentId) {
		Set<UUID> examIds = enrollmentRepository.findAllByStudentId(studentId)
			.stream()
			.map(ExamEnrollment::getExamId)
			.collect(Collectors.toSet());
		return examRepository.findLiveKlassExamsByIds(examIds)
			.stream()
			.map(Exam::getId)
			.collect(Collectors.toSet());
	}

	private static String validateName(String name) {
		String value = name == null ? "" : name.strip();
		if (value.isEmpty()) {
			throw invalid("name", "Name is required");
		}
		if (value.length() > ExamLeaderboard.MAX_NAME_LENGTH) {
			throw invalid("name", "Name must be at most " + ExamLeaderboard.MAX_NAME_LENGTH + " characters");
		}
		return value;
	}

	/** At least two distinct live Klass exams of this tenant (404 otherwise), all within {@code scope} (403). */
	private List<Exam> validateExams(List<UUID> examIds, Set<UUID> scope) {
		Set<UUID> distinct = examIds == null ? Set.of()
				: examIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		if (distinct.size() < MIN_EXAMS) {
			throw invalid("examIds", "Choose at least " + MIN_EXAMS + " exams");
		}
		List<Exam> exams = new ArrayList<>(examRepository.findLiveKlassExamsByIds(distinct));
		if (exams.size() != distinct.size()) {
			throw new NotFoundException("Exam not found");
		}
		if (scope != null && exams.stream().anyMatch(exam -> !scope.contains(exam.getCourseId()))) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		exams.sort(EXAM_ORDER);
		return exams;
	}

	private LeaderboardDetailView detail(ExamLeaderboard leaderboard, List<Exam> exams) {
		Map<UUID, String> courseNames = courseLookupApi
			.getCourseSummaries(exams.stream().map(Exam::getCourseId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
		List<LeaderboardExamView> views = exams.stream()
			.map(exam -> new LeaderboardExamView(exam.getId(), exam.getTitle(), exam.getExamType(), exam.getCourseId(),
					courseNames.getOrDefault(exam.getCourseId(), ""), exam.getTotalMarks(),
					exam.getResultsPublishedAt() != null))
			.toList();
		return new LeaderboardDetailView(leaderboard.getId(), leaderboard.getName(), leaderboard.isPublished(),
				exams.stream().map(Exam::getId).toList(), views, leaderboard.getCreatedAt(), leaderboard.getUpdatedAt());
	}

	private static Map<String, Object> snapshot(ExamLeaderboard leaderboard, List<Exam> exams) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", leaderboard.getName());
		snapshot.put("published", leaderboard.isPublished());
		snapshot.put("examIds", exams.stream().map(exam -> exam.getId().toString()).toList());
		return snapshot;
	}

	private void audit(String action, UUID leaderboardId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), action, "exam_leaderboard",
				leaderboardId, null, metadata));
	}

	private static UUID requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only students can view their leaderboards");
		}
		return principal.userId();
	}

	private static String needle(String search) {
		return search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException(message, List.of(new FieldError(field, message)));
	}

	private static NotFoundException notFound() {
		return new NotFoundException("Leaderboard not found");
	}

}
