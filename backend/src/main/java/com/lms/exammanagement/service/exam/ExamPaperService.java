package com.lms.exammanagement.service.exam;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.domain.ExamPaperItem;
import com.lms.exammanagement.domain.ExamPaperOption;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.repository.ExamPaperItemRepository;
import com.lms.exammanagement.repository.ExamPaperOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The frozen MCQ paper (D3, V112): draws {@code quota} random live questions per module and
 * snapshots them (body, marks, explanation, options + correct). Callers (ExamConfigService)
 * have already authorized the request, validated the quotas' modules against the exam's
 * course and checked that no attempt exists.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExamPaperService {

	private static final Comparator<ExamQuestionOption> OPTION_ORDER = Comparator
		.comparing(ExamQuestionOption::getSequence, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(ExamQuestionOption::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(ExamQuestionOption::getId);

	private final ExamQuestionRepository questionRepository;

	private final ExamQuestionOptionRepository questionOptionRepository;

	private final ExamPaperItemRepository itemRepository;

	private final ExamPaperOptionRepository optionRepository;

	private final ExamPaperRandomSource randomSource;

	private final TenantContext tenantContext;

	public ExamPaperService(ExamQuestionRepository questionRepository,
			ExamQuestionOptionRepository questionOptionRepository, ExamPaperItemRepository itemRepository,
			ExamPaperOptionRepository optionRepository, ExamPaperRandomSource randomSource,
			TenantContext tenantContext) {
		this.questionRepository = questionRepository;
		this.questionOptionRepository = questionOptionRepository;
		this.itemRepository = itemRepository;
		this.optionRepository = optionRepository;
		this.randomSource = randomSource;
		this.tenantContext = tenantContext;
	}

	/** An in-memory draw, not yet persisted. */
	public record DrawnPaper(List<ExamQuestion> questions, BigDecimal totalMarks) {
	}

	/**
	 * Draws {@code quota} distinct live questions from each module (iteration order of
	 * {@code quotas} = paper order; within a module, bank order).
	 * @param fieldPrefix field name used for "not enough questions" errors, e.g. {@code moduleQuotas}
	 * @throws FieldValidationException if a module has fewer live questions than its quota
	 */
	public DrawnPaper draw(LinkedHashMap<UUID, Integer> quotas, String fieldPrefix) {
		RandomGenerator random = randomSource.generator();
		List<FieldError> errors = new ArrayList<>();
		List<ExamQuestion> drawn = new ArrayList<>();
		int index = 0;
		for (Map.Entry<UUID, Integer> quota : quotas.entrySet()) {
			int wanted = quota.getValue();
			if (wanted > 0) {
				List<ExamQuestion> candidates = questionRepository.findAllLiveByModuleId(quota.getKey());
				if (wanted > candidates.size()) {
					errors.add(new FieldError(fieldPrefix + "[" + index + "].questionCount",
							"Only " + candidates.size() + " question" + (candidates.size() == 1 ? " is" : "s are")
									+ " available in this module"));
				}
				else {
					drawn.addAll(ExamPaperDrawer.pick(candidates, wanted, random));
				}
			}
			index++;
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Some modules do not have enough questions", errors);
		}
		BigDecimal total = drawn.stream().map(ExamQuestion::getMarks).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new DrawnPaper(drawn, total);
	}

	/**
	 * Inserts {@code paper} as the exam's paper. The exam must have no paper rows (call
	 * {@link #deletePaper} first on a redraw - rows are deleted, never updated).
	 */
	public void insertPaper(UUID examId, DrawnPaper paper) {
		UUID tenantId = tenantContext.getTenantId();
		Set<UUID> questionIds = paper.questions().stream().map(ExamQuestion::getId).collect(Collectors.toSet());
		Map<UUID, List<ExamQuestionOption>> optionsByQuestion = new HashMap<>();
		for (ExamQuestionOption option : questionOptionRepository.findAllByQuestionIdIn(questionIds)) {
			optionsByQuestion.computeIfAbsent(option.getQuestionId(), key -> new ArrayList<>()).add(option);
		}
		List<ExamPaperItem> items = new ArrayList<>();
		int sequence = 0;
		for (ExamQuestion question : paper.questions()) {
			items.add(new ExamPaperItem(tenantId, examId, ++sequence, question.getId(), question.getBody(),
					question.getMarks(), question.getExplanation()));
		}
		items = itemRepository.saveAll(items);
		List<ExamPaperOption> options = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			ExamPaperItem item = items.get(i);
			List<ExamQuestionOption> source = new ArrayList<>(
					optionsByQuestion.getOrDefault(paper.questions().get(i).getId(), List.of()));
			source.sort(OPTION_ORDER);
			long correct = source.stream().filter(ExamQuestionOption::isCorrect).count();
			if (source.size() < 2 || correct != 1) {
				// Module bank questions always satisfy D1 (Slice A); never freeze one that does not.
				throw new IllegalStateException("Bank question " + item.getSourceQuestionId() + " is not a valid MCQ");
			}
			int optionSequence = 0;
			for (ExamQuestionOption option : source) {
				options.add(new ExamPaperOption(tenantId, item.getId(), ++optionSequence, option.getOptionText(),
						option.isCorrect()));
			}
		}
		optionRepository.saveAll(options);
		itemRepository.flush();
	}

	public void deletePaper(UUID examId) {
		itemRepository.deleteAllByExamId(examId);
	}

	public int paperQuestionCount(UUID examId) {
		return (int) itemRepository.countByExamId(examId);
	}

}
