package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.ExamType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of Klass exam results publication, Notify Marks, stats and leaderboards
 * (contract {@code docs/api/exam-management-v2.md} "Slice E"). Plain DTO records - never JPA
 * entities, never answer keys outside the staff stats, never the exam password.
 */
public final class ExamResultsViews {

	private ExamResultsViews() {
	}

	// ------------------------------------------------------------------
	// Notify Marks.
	// ------------------------------------------------------------------

	/** {@code recipients} = enrolled students with marks; {@code withoutMarks} = the other enrolled students. */
	public record NotifyPreviewView(long recipients, long withoutMarks, Instant alreadyNotifiedAt,
			Instant resultsPublishedAt, Instant endAt, boolean ended) {
	}

	/** One accepted send (or its replay, {@code replayed = true}). */
	public record NotifyMarksView(UUID dispatchId, int recipients, int withoutMarks, boolean resend, Instant sentAt,
			Instant resultsPublishedAt, boolean replayed) {
	}

	// ------------------------------------------------------------------
	// Stats.
	// ------------------------------------------------------------------

	public record PopulationView(long enrolled, long attended, long graded) {
	}

	public record OptionStatsView(UUID id, String html, boolean correct, long selectedCount) {
	}

	public record QuestionStatsView(int sequence, String bodyHtml, BigDecimal marks, long correct, long incorrect,
			long unanswered, BigDecimal correctPercent, List<OptionStatsView> options) {
	}

	public record ExamStatsView(UUID examId, ExamType type, PopulationView population, BigDecimal averageMarks,
			BigDecimal maxMarks, BigDecimal minMarks, Long passed, long passedOf, BigDecimal cutoffMarks,
			BigDecimal totalMarks, List<QuestionStatsView> questions, List<Integer> frequentlyMissed) {
	}

	// ------------------------------------------------------------------
	// Leaderboards.
	// ------------------------------------------------------------------

	public record LeaderboardSummaryView(UUID id, String name, int examCount, boolean published) {
	}

	public record LeaderboardExamView(UUID id, String name, ExamType type, UUID courseId, String courseName,
			BigDecimal totalMarks, boolean published) {
	}

	public record LeaderboardDetailView(UUID id, String name, boolean published, List<UUID> examIds,
			List<LeaderboardExamView> exams, Instant createdAt, Instant updatedAt) {
	}

	public record RankExamView(UUID id, String name, BigDecimal totalMarks, boolean published) {
	}

	public record PerExamMarksView(UUID examId, BigDecimal marks) {
	}

	/**
	 * {@code self} is only meaningful on the student endpoints (the caller's own row). On the
	 * student endpoints other students' rows carry no {@code studentId}, {@code indexNumber} or
	 * {@code perExam}.
	 */
	public record RankRowView(int rank, UUID studentId, String indexNumber, String name, BigDecimal totalMarks,
			BigDecimal maxPossible, BigDecimal share, List<PerExamMarksView> perExam, boolean self) {
	}

	public record LeaderboardRanksView(UUID id, String name, boolean published, List<RankExamView> exams,
			List<RankRowView> rows, int page, int size, long totalElements, int totalPages, boolean hasPublishedMarks) {
	}

	public record StudentLeaderboardView(UUID id, String name, int examCount) {
	}

}
