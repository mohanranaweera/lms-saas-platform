package com.lms.exammanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.exammanagement.ExamManagementTestSupport;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.domain.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves that {@link com.lms.common.persistence.TenantAwareRepository}'s
 * STRUCTURAL tenant filtering (never an ad hoc {@code WHERE} clause, per
 * {@code .claude/rules/backend.md}) genuinely applies to all six new
 * MVP-017 tables - not merely "the service layer happens to always pass the
 * right tenant". Every exam-management repository here uses only the
 * inherited {@code findById}/{@code findAll} default methods (no bespoke
 * {@code *Unchecked}-style method exists in this module, unlike {@code
 * AttendanceRecordRepository#upsertRecord}), so this is a direct proof of
 * the shared base class's own behavior for THIS module's entities -
 * complementing (not duplicating) the HTTP-level {@code
 * ExamCrossTenantIntegrationTest}.
 *
 * <p>Lives in {@code com.lms.exammanagement.repository} (mirroring {@code
 * AttendanceRecordRepositoryTenantGuardTest}'s exact package placement) so it
 * can autowire these package-private-facing repositories directly.
 */
class ExamRepositoryTenantGuardIntegrationTest extends ExamManagementTestSupport {

	@Autowired
	private ExamQuestionRepository examQuestionRepository;

	@Autowired
	private ExamQuestionOptionRepository examQuestionOptionRepository;

	@Autowired
	private ExamRepository examRepository;

	@Autowired
	private ExamQuestionLinkRepository examQuestionLinkRepository;

	@Autowired
	private ExamAttemptRepository examAttemptRepository;

	@Autowired
	private ExamAnswerRepository examAnswerRepository;

	@Test
	void examQuestionIsInvisibleToAnotherTenantByIdAndByListing() {
		ExamFixture tenantA = seedExamFixture("erg-question-a");
		ExamFixture tenantB = seedExamFixture("erg-question-b");
		ExamQuestion saved = withTenant(tenantA.tenant().getId(),
				() -> examQuestionRepository.save(new ExamQuestion(tenantA.tenant().getId(), tenantA.course().id(),
						QuestionType.STRUCTURED, "Explain")));

		var foundUnderB = withTenant(tenantB.tenant().getId(), () -> examQuestionRepository.findById(saved.getId()));
		var listUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionRepository.findByCourseId(tenantA.course().id(),
						org.springframework.data.domain.PageRequest.of(0, 20)));

		assertThat(foundUnderB).isEmpty();
		assertThat(listUnderB.getContent()).isEmpty();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(), () -> examQuestionRepository.findById(saved.getId()));
		assertThat(foundUnderA).isPresent();
	}

	@Test
	void examQuestionOptionIsInvisibleToAnotherTenant() {
		ExamFixture tenantA = seedExamFixture("erg-option-a");
		ExamFixture tenantB = seedExamFixture("erg-option-b");
		ExamQuestion question = withTenant(tenantA.tenant().getId(),
				() -> examQuestionRepository.save(
						new ExamQuestion(tenantA.tenant().getId(), tenantA.course().id(), QuestionType.MCQ, "2+2?")));
		ExamQuestionOption savedOption = withTenant(tenantA.tenant().getId(), () -> examQuestionOptionRepository
			.save(new ExamQuestionOption(tenantA.tenant().getId(), question.getId(), "4", true)));

		var foundUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionOptionRepository.findById(savedOption.getId()));
		var listUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionOptionRepository.findAllByQuestionId(question.getId()));

		assertThat(foundUnderB).isEmpty();
		assertThat(listUnderB).isEmpty();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(),
				() -> examQuestionOptionRepository.findById(savedOption.getId()));
		var listUnderA = withTenant(tenantA.tenant().getId(),
				() -> examQuestionOptionRepository.findAllByQuestionId(question.getId()));
		assertThat(foundUnderA).isPresent();
		assertThat(listUnderA).isNotEmpty();
	}

	@Test
	void examIsInvisibleToAnotherTenant() {
		ExamFixture tenantA = seedExamFixture("erg-exam-a");
		ExamFixture tenantB = seedExamFixture("erg-exam-b");
		Instant start = Instant.now().plusSeconds(3600);
		Exam savedExam = withTenant(tenantA.tenant().getId(),
				() -> examRepository.save(new Exam(tenantA.tenant().getId(), tenantA.course().id(), "Midterm", start,
						start.plusSeconds(3600), 60, ExamStatus.DRAFT)));

		var foundUnderB = withTenant(tenantB.tenant().getId(), () -> examRepository.findById(savedExam.getId()));
		var listUnderB = withTenant(tenantB.tenant().getId(),
				() -> examRepository.findByCourseIdInAndStatusIn(java.util.Set.of(tenantA.course().id()),
						List.of(ExamStatus.DRAFT, ExamStatus.SCHEDULED, ExamStatus.PUBLISHED, ExamStatus.CLOSED),
						org.springframework.data.domain.PageRequest.of(0, 20)));

		assertThat(foundUnderB).isEmpty();
		assertThat(listUnderB.getContent()).isEmpty();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(), () -> examRepository.findById(savedExam.getId()));
		var listUnderA = withTenant(tenantA.tenant().getId(),
				() -> examRepository.findByCourseIdInAndStatusIn(java.util.Set.of(tenantA.course().id()),
						List.of(ExamStatus.DRAFT, ExamStatus.SCHEDULED, ExamStatus.PUBLISHED, ExamStatus.CLOSED),
						org.springframework.data.domain.PageRequest.of(0, 20)));
		assertThat(foundUnderA).isPresent();
		assertThat(listUnderA.getContent()).isNotEmpty();
	}

	@Test
	void examQuestionLinkIsInvisibleToAnotherTenant() {
		ExamFixture tenantA = seedExamFixture("erg-link-a");
		ExamFixture tenantB = seedExamFixture("erg-link-b");
		Instant start = Instant.now().plusSeconds(3600);
		Exam exam = withTenant(tenantA.tenant().getId(),
				() -> examRepository.save(new Exam(tenantA.tenant().getId(), tenantA.course().id(), "Midterm", start,
						start.plusSeconds(3600), 60, ExamStatus.DRAFT)));
		ExamQuestion question = withTenant(tenantA.tenant().getId(),
				() -> examQuestionRepository.save(
						new ExamQuestion(tenantA.tenant().getId(), tenantA.course().id(), QuestionType.MCQ, "2+2?")));
		withTenant(tenantA.tenant().getId(), () -> examQuestionOptionRepository
			.save(new ExamQuestionOption(tenantA.tenant().getId(), question.getId(), "4", true)));
		ExamQuestionLink savedLink = withTenant(tenantA.tenant().getId(), () -> examQuestionLinkRepository
			.save(new ExamQuestionLink(tenantA.tenant().getId(), exam.getId(), question.getId(), 1)));

		var foundUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionLinkRepository.findById(savedLink.getId()));
		var listUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionLinkRepository.findAllByExamIdOrderBySequence(exam.getId()));
		var existsUnderB = withTenant(tenantB.tenant().getId(),
				() -> examQuestionLinkRepository.existsByExamId(exam.getId()));

		assertThat(foundUnderB).isEmpty();
		assertThat(listUnderB).isEmpty();
		assertThat(existsUnderB).isFalse();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(),
				() -> examQuestionLinkRepository.findById(savedLink.getId()));
		var listUnderA = withTenant(tenantA.tenant().getId(),
				() -> examQuestionLinkRepository.findAllByExamIdOrderBySequence(exam.getId()));
		var existsUnderA = withTenant(tenantA.tenant().getId(),
				() -> examQuestionLinkRepository.existsByExamId(exam.getId()));
		assertThat(foundUnderA).isPresent();
		assertThat(listUnderA).isNotEmpty();
		assertThat(existsUnderA).isTrue();
	}

	@Test
	void examAttemptIsInvisibleToAnotherTenant() {
		ExamFixture tenantA = seedExamFixture("erg-attempt-a");
		ExamFixture tenantB = seedExamFixture("erg-attempt-b");
		Instant start = Instant.now().plusSeconds(3600);
		Exam exam = withTenant(tenantA.tenant().getId(),
				() -> examRepository.save(new Exam(tenantA.tenant().getId(), tenantA.course().id(), "Midterm", start,
						start.plusSeconds(3600), 60, ExamStatus.DRAFT)));
		ExamAttempt savedAttempt = withTenant(tenantA.tenant().getId(),
				() -> examAttemptRepository.save(new ExamAttempt(tenantA.tenant().getId(), exam.getId(),
						tenantA.student().getId(), Instant.now())));

		var foundUnderB = withTenant(tenantB.tenant().getId(),
				() -> examAttemptRepository.findById(savedAttempt.getId()));
		var inProgressUnderB = withTenant(tenantB.tenant().getId(), () -> examAttemptRepository
			.findInProgressByExamIdAndStudentId(exam.getId(), tenantA.student().getId()));
		var listUnderB = withTenant(tenantB.tenant().getId(),
				() -> examAttemptRepository.findAllByExamId(exam.getId()));

		assertThat(foundUnderB).isEmpty();
		assertThat(inProgressUnderB).isEmpty();
		assertThat(listUnderB).isEmpty();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(),
				() -> examAttemptRepository.findById(savedAttempt.getId()));
		var inProgressUnderA = withTenant(tenantA.tenant().getId(), () -> examAttemptRepository
			.findInProgressByExamIdAndStudentId(exam.getId(), tenantA.student().getId()));
		var listUnderA = withTenant(tenantA.tenant().getId(),
				() -> examAttemptRepository.findAllByExamId(exam.getId()));
		assertThat(foundUnderA).isPresent();
		assertThat(inProgressUnderA).isPresent();
		assertThat(listUnderA).isNotEmpty();
	}

	@Test
	void examAnswerIsInvisibleToAnotherTenant() {
		ExamFixture tenantA = seedExamFixture("erg-answer-a");
		ExamFixture tenantB = seedExamFixture("erg-answer-b");
		Instant start = Instant.now().plusSeconds(3600);
		Exam exam = withTenant(tenantA.tenant().getId(),
				() -> examRepository.save(new Exam(tenantA.tenant().getId(), tenantA.course().id(), "Midterm", start,
						start.plusSeconds(3600), 60, ExamStatus.DRAFT)));
		ExamQuestion question = withTenant(tenantA.tenant().getId(),
				() -> examQuestionRepository.save(new ExamQuestion(tenantA.tenant().getId(), tenantA.course().id(),
						QuestionType.STRUCTURED, "Explain")));
		ExamAttempt attempt = withTenant(tenantA.tenant().getId(),
				() -> examAttemptRepository.save(new ExamAttempt(tenantA.tenant().getId(), exam.getId(),
						tenantA.student().getId(), Instant.now())));
		ExamAnswer savedAnswer = withTenant(tenantA.tenant().getId(),
				() -> examAnswerRepository.save(new ExamAnswer(tenantA.tenant().getId(), attempt.getId(),
						question.getId(), exam.getId(), "my answer")));

		var foundUnderB = withTenant(tenantB.tenant().getId(),
				() -> examAnswerRepository.findById(savedAnswer.getId()));
		var byAttemptUnderB = withTenant(tenantB.tenant().getId(),
				() -> examAnswerRepository.findAllByAttemptId(attempt.getId()));
		var byExamUnderB = withTenant(tenantB.tenant().getId(),
				() -> examAnswerRepository.findAllByExamId(exam.getId(),
						org.springframework.data.domain.PageRequest.of(0, 20)));

		assertThat(foundUnderB).isEmpty();
		assertThat(byAttemptUnderB).isEmpty();
		assertThat(byExamUnderB.getContent()).isEmpty();
		// Sanity: tenant A's own context DOES see it.
		var foundUnderA = withTenant(tenantA.tenant().getId(),
				() -> examAnswerRepository.findById(savedAnswer.getId()));
		var byAttemptUnderA = withTenant(tenantA.tenant().getId(),
				() -> examAnswerRepository.findAllByAttemptId(attempt.getId()));
		var byExamUnderA = withTenant(tenantA.tenant().getId(),
				() -> examAnswerRepository.findAllByExamId(exam.getId(),
						org.springframework.data.domain.PageRequest.of(0, 20)));
		assertThat(foundUnderA).isPresent();
		assertThat(byAttemptUnderA).isNotEmpty();
		assertThat(byExamUnderA.getContent()).isNotEmpty();
	}

}
