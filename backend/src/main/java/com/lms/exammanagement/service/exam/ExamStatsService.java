package com.lms.exammanagement.service.exam;

import com.lms.contentmanagement.api.ExamQuestionMediaApi;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamStatsQueries;
import com.lms.exammanagement.repository.ExamStatsQueries.OptionCounts;
import com.lms.exammanagement.repository.ExamStatsQueries.QuestionCounts;
import com.lms.exammanagement.service.exam.ExamParticipationReader.Counts;
import com.lms.exammanagement.service.exam.ExamParticipationReader.StudentFacts;
import com.lms.exammanagement.service.exam.ExamResultsViews.ExamStatsView;
import com.lms.exammanagement.service.exam.ExamResultsViews.OptionStatsView;
import com.lms.exammanagement.service.exam.ExamResultsViews.PopulationView;
import com.lms.exammanagement.service.exam.ExamResultsViews.QuestionStatsView;
import com.lms.exammanagement.service.questionbank.RichContentSanitizer;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass exam stats ({@code GET /exams/{id}/stats}, contract "Slice E"; Klass "Passed {passed} of
 * {facedStudents}").
 *
 * <ul>
 * <li>{@code population}: {@code enrolled} / {@code attended} from {@link
 * ExamParticipationReader#countsFor} (the same numbers as the management summary and the
 * directory card); {@code graded} = attended students with marks.</li>
 * <li>{@code averageMarks} / {@code maxMarks} / {@code minMarks} over graded attended students,
 * {@code null} when there are none (never NaN).</li>
 * <li>{@code passed} = graded attended students with marks &gt;= cutoff, {@code passedOf} =
 * attended; {@code passed} is {@code null} without a cutoff or with nobody attended.</li>
 * <li>MCQ {@code questions}: correct / incorrect / unanswered and option selection counts over
 * finalized graded attempts (two grouped SQL statements, {@link ExamStatsQueries}); {@code
 * frequentlyMissed} = up to 5 sequences by incorrect + unanswered (ties: lower sequence).</li>
 * </ul>
 *
 * <p>Authorization: course authoring access with {@code VIEW} (owning teacher, teacher
 * assistant, staff with {@code DomainArea.EXAMS}); students 403; another tenant 404. Overdue
 * attempts are finalized and enroll-all participants materialized first, hence not read-only.
 */
@Service
@Transactional
public class ExamStatsService {

	static final int FREQUENTLY_MISSED_LIMIT = 5;

	private final ExamConfigService configService;

	private final ExamEnrollmentService enrollmentService;

	private final ExamParticipationReader participationReader;

	private final ExamOverdueAttempts overdueAttempts;

	private final ExamStatsQueries statsQueries;

	private final ExamQuestionMediaApi mediaApi;

	private final ExamAccessGuard accessGuard;

	private final Clock clock;

	public ExamStatsService(ExamConfigService configService, ExamEnrollmentService enrollmentService,
			ExamParticipationReader participationReader, ExamOverdueAttempts overdueAttempts,
			ExamStatsQueries statsQueries, ExamQuestionMediaApi mediaApi, ExamAccessGuard accessGuard, Clock clock) {
		this.mediaApi = mediaApi;
		this.configService = configService;
		this.enrollmentService = enrollmentService;
		this.participationReader = participationReader;
		this.overdueAttempts = overdueAttempts;
		this.statsQueries = statsQueries;
		this.accessGuard = accessGuard;
		this.clock = clock;
	}

	public ExamStatsView stats(UUID examId) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		Instant now = clock.instant();
		enrollmentService.materializeEnrollAll(exam);
		overdueAttempts.finalizeOverdue(exam, now);

		Counts counts = participationReader.countsFor(List.of(exam)).getOrDefault(exam.getId(), Counts.ZERO);
		List<BigDecimal> marks = participationReader.studentFacts(List.of(exam))
			.getOrDefault(exam.getId(), List.of())
			.stream()
			.filter(student -> student.attended() && student.graded())
			.map(StudentFacts::marks)
			.toList();

		BigDecimal average = null;
		BigDecimal max = null;
		BigDecimal min = null;
		if (!marks.isEmpty()) {
			BigDecimal sum = marks.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			average = sum.divide(BigDecimal.valueOf(marks.size()), 2, RoundingMode.HALF_UP);
			max = marks.stream().max(Comparator.naturalOrder()).orElseThrow();
			min = marks.stream().min(Comparator.naturalOrder()).orElseThrow();
		}
		BigDecimal cutoff = exam.getCutoffMarks();
		Long passed = cutoff == null || counts.attended() == 0 ? null
				: marks.stream().filter(value -> value.compareTo(cutoff) >= 0).count();

		List<QuestionStatsView> questions = exam.getExamType() == ExamType.MCQ ? questions(exam.getId()) : List.of();
		List<Integer> frequentlyMissed = questions.stream()
			.filter(question -> question.incorrect() + question.unanswered() > 0)
			.sorted(Comparator
				.comparingLong((QuestionStatsView question) -> question.incorrect() + question.unanswered())
				.reversed()
				.thenComparingInt(QuestionStatsView::sequence))
			.limit(FREQUENTLY_MISSED_LIMIT)
			.map(QuestionStatsView::sequence)
			.toList();

		return new ExamStatsView(exam.getId(), exam.getExamType(),
				new PopulationView(counts.enrolled(), counts.attended(), marks.size()), average, max, min, passed,
				counts.attended(), cutoff, exam.getTotalMarks(), questions, frequentlyMissed);
	}

	private List<QuestionStatsView> questions(UUID examId) {
		List<OptionCounts> optionCounts = statsQueries.optionCounts(examId);
		List<QuestionCounts> questionCounts = statsQueries.questionCounts(examId);
		Set<UUID> mediaIds = new HashSet<>();
		questionCounts.forEach(question -> mediaIds.addAll(RichContentSanitizer.mediaIdsIn(question.bodyHtml())));
		optionCounts.forEach(option -> mediaIds.addAll(RichContentSanitizer.mediaIdsIn(option.html())));
		Map<UUID, String> urls = mediaIds.isEmpty() ? Map.of()
				: mediaApi.signImageUrls(mediaIds, StudentExamService.IMAGE_URL_TTL);
		Map<UUID, List<OptionStatsView>> options = new HashMap<>();
		for (OptionCounts option : optionCounts) {
			options.computeIfAbsent(option.paperItemId(), id -> new ArrayList<>())
				.add(new OptionStatsView(option.optionId(), RichContentSanitizer.withSignedSources(option.html(), urls),
						option.correct(), option.selectedCount()));
		}
		List<QuestionStatsView> questions = new ArrayList<>();
		for (QuestionCounts question : questionCounts) {
			long total = question.correct() + question.incorrect() + question.unanswered();
			BigDecimal correctPercent = total == 0 ? null
					: BigDecimal.valueOf(question.correct() * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
			questions.add(new QuestionStatsView(question.sequence(),
					RichContentSanitizer.withSignedSources(question.bodyHtml(), urls), question.marks(),
					question.correct(), question.incorrect(), question.unanswered(), correctPercent,
					options.getOrDefault(question.paperItemId(), List.of())));
		}
		return questions;
	}

}
