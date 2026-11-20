package com.lms.exammanagement.service.exam;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.ExamParticipationReader.Counts;
import com.lms.exammanagement.service.exam.ExamViews.ExamCardView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDirectoryView;
import com.lms.exammanagement.support.ExamAccessGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass exam directory ({@code GET /api/v1/exams/directory}): the caller's authorized live
 * Klass exams (teacher: own courses; teacher assistant / staff with {@code EXAMS}/{@code
 * VIEW}: whole tenant; students 403), grouped by the server-derived status at one {@code
 * serverNow} instant from the injected clock. Search is case-insensitive on exam name and
 * course name. Cards never carry the password, paper content or answers. Legacy MVP-017
 * exams are not listed.
 */
@Service
@Transactional(readOnly = true)
public class ExamDirectoryService {

	private final ExamRepository examRepository;

	private final ExamAccessGuard accessGuard;

	private final CourseLookupApi courseLookupApi;

	private final ExamParticipationReader participationReader;

	private final Clock clock;

	public ExamDirectoryService(ExamRepository examRepository, ExamAccessGuard accessGuard,
			CourseLookupApi courseLookupApi, ExamParticipationReader participationReader, Clock clock) {
		this.examRepository = examRepository;
		this.accessGuard = accessGuard;
		this.courseLookupApi = courseLookupApi;
		this.participationReader = participationReader;
		this.clock = clock;
	}

	public ExamDirectoryView directory(String search) {
		Set<UUID> courseScope = accessGuard.resolveDirectoryCourseScope();
		Instant now = clock.instant();
		List<Exam> exams = examRepository.findLiveKlassExams(courseScope);
		Map<UUID, String> courseNames = courseLookupApi
			.getCourseSummaries(exams.stream().map(Exam::getCourseId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name));

		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		List<Exam> matching = exams.stream()
			.filter(exam -> needle.isEmpty() || exam.getTitle().toLowerCase(Locale.ROOT).contains(needle)
					|| courseNames.getOrDefault(exam.getCourseId(), "").toLowerCase(Locale.ROOT).contains(needle))
			.toList();
		Map<UUID, Counts> counts = participationReader.countsFor(matching);

		List<ExamCardView> inProgress = new ArrayList<>();
		List<ExamCardView> upcoming = new ArrayList<>();
		List<ExamCardView> previous = new ArrayList<>();
		for (Exam exam : matching) {
			ExamWindowStatus status = ExamWindowStatus.of(now, exam.getScheduledStart(), exam.getScheduledEnd());
			Counts count = counts.getOrDefault(exam.getId(), Counts.ZERO);
			ExamCardView card = new ExamCardView(exam.getId(), exam.getExamType(), status, exam.getTitle(),
					exam.getCourseId(), courseNames.getOrDefault(exam.getCourseId(), ""), exam.getScheduledStart(),
					exam.getScheduledEnd(), exam.getTimeLimitMinutes(),
					exam.getExamType() == ExamType.MCQ ? exam.getQuestionCount() : null, exam.getTotalMarks(),
					count.enrolled(), count.attended(), count.completed(), count.submitted());
			switch (status) {
				case IN_PROGRESS -> inProgress.add(card);
				case UPCOMING -> upcoming.add(card);
				case FINISHED -> previous.add(card);
			}
		}
		inProgress.sort(Comparator.comparing(ExamCardView::endAt).thenComparing(ExamCardView::id));
		upcoming.sort(Comparator.comparing(ExamCardView::startAt).thenComparing(ExamCardView::id));
		previous.sort(Comparator.comparing(ExamCardView::endAt).reversed().thenComparing(ExamCardView::id));
		return new ExamDirectoryView(now, inProgress, upcoming, previous);
	}

}
