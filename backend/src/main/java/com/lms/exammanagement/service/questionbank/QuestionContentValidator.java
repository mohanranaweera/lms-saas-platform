package com.lms.exammanagement.service.questionbank;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.contentmanagement.api.ExamQuestionMediaApi;
import com.lms.exammanagement.service.questionbank.RichContentSanitizer.Sanitized;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates and sanitizes a module-bank question upsert (contract "Slice A" rules): body
 * non-empty; {@code 0 < marks <= 100} with at most 2 decimals (absent = 1); 2-10 options,
 * each non-empty, <b>exactly one</b> correct (D1); explanation optional (empty = null);
 * every referenced image is a live {@code EXAM_QUESTION} media file of the caller's tenant.
 * A TEACHER / TEACHER_ASSISTANT may only reference images they uploaded themselves or that
 * the question already references (mirrors the exam-document rule in
 * {@code ExamConfigService}); other staff with exam authoring access keep tenant-wide access.
 * Any failure is one 400 carrying every field error found.
 */
@Component
public class QuestionContentValidator {

	public static final int MIN_OPTIONS = 2;

	public static final int MAX_OPTIONS = 10;

	public static final BigDecimal MAX_MARKS = new BigDecimal("100");

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private final ExamQuestionMediaApi examQuestionMediaApi;

	public QuestionContentValidator(ExamQuestionMediaApi examQuestionMediaApi) {
		this.examQuestionMediaApi = examQuestionMediaApi;
	}

	public record OptionInput(UUID id, String html, boolean correct) {
	}

	public record QuestionInput(String bodyHtml, BigDecimal marks, String explanationHtml, List<OptionInput> options) {
	}

	public record ValidOption(UUID id, String html, boolean correct) {
	}

	public record ValidQuestion(String bodyHtml, BigDecimal marks, String explanationHtml, List<ValidOption> options) {
	}

	/** A new question: no image is referenced yet. */
	public ValidQuestion validate(QuestionInput input) {
		return validate(input, Set.of());
	}

	/**
	 * @param existingMediaIds images the stored question already references (update) - a
	 * teacher may keep these even when another user uploaded them
	 */
	public ValidQuestion validate(QuestionInput input, Set<UUID> existingMediaIds) {
		List<FieldError> errors = new ArrayList<>();
		Map<String, Set<UUID>> mediaByField = new LinkedHashMap<>();

		Sanitized body = RichContentSanitizer.sanitize(input.bodyHtml());
		checkContent(body, "bodyHtml", "The question", errors, mediaByField);

		BigDecimal marks = input.marks() == null ? BigDecimal.ONE : input.marks();
		String marksProblem = marksProblem(marks);
		if (marksProblem != null) {
			errors.add(new FieldError("marks", marksProblem));
		}

		String explanation = null;
		if (input.explanationHtml() != null) {
			Sanitized sanitized = RichContentSanitizer.sanitize(input.explanationHtml());
			if (sanitized.problem() != null) {
				errors.add(new FieldError("explanationHtml", sanitized.problem()));
			}
			if (sanitized.nonEmpty()) {
				explanation = sanitized.html();
				if (!sanitized.mediaIds().isEmpty()) {
					mediaByField.put("explanationHtml", sanitized.mediaIds());
				}
			}
		}

		List<OptionInput> options = input.options() == null ? List.of() : input.options();
		List<ValidOption> validOptions = new ArrayList<>();
		if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
			errors.add(new FieldError("options",
					"A question needs between " + MIN_OPTIONS + " and " + MAX_OPTIONS + " answers"));
		}
		long correctCount = options.stream().filter(option -> option != null && option.correct()).count();
		if (correctCount != 1) {
			errors.add(new FieldError("options", "Exactly one answer must be marked correct"));
		}
		Set<UUID> seenIds = new HashSet<>();
		for (int i = 0; i < options.size(); i++) {
			OptionInput option = options.get(i);
			String field = "options[" + i + "].html";
			if (option == null) {
				errors.add(new FieldError(field, "The answer must not be empty"));
				continue;
			}
			if (option.id() != null && !seenIds.add(option.id())) {
				errors.add(new FieldError("options[" + i + "].id", "The same answer id is used twice"));
			}
			Sanitized sanitized = RichContentSanitizer.sanitize(option.html());
			checkContent(sanitized, field, "The answer", errors, mediaByField);
			validOptions.add(new ValidOption(option.id(), sanitized.html(), option.correct()));
		}

		Set<UUID> allMedia = new HashSet<>();
		mediaByField.values().forEach(allMedia::addAll);
		if (!allMedia.isEmpty()) {
			Map<UUID, UUID> uploaders = examQuestionMediaApi.findLiveImageUploaders(allMedia);
			AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
			boolean authorRole = TEACHER_ROLE.equals(principal.role())
					|| TEACHER_ASSISTANT_ROLE.equals(principal.role());
			mediaByField.forEach((field, ids) -> {
				boolean usable = ids.stream()
					.allMatch(id -> uploaders.containsKey(id) && (!authorRole || existingMediaIds.contains(id)
							|| principal.userId().equals(uploaders.get(id))));
				if (!usable) {
					// Same message for missing and foreign images: no existence oracle.
					errors.add(new FieldError(field, "An image is not available - upload it again"));
				}
			});
		}

		if (!errors.isEmpty()) {
			throw new FieldValidationException("The question is not valid", errors);
		}
		return new ValidQuestion(body.html(), marks.setScale(2), explanation, List.copyOf(validOptions));
	}

	/** @return a client-facing problem, or {@code null} when {@code marks} is acceptable. */
	public static String marksProblem(BigDecimal marks) {
		if (marks == null) {
			return "Marks are required";
		}
		if (marks.signum() <= 0) {
			return "Marks must be greater than 0";
		}
		if (marks.compareTo(MAX_MARKS) > 0) {
			return "Marks must be at most 100";
		}
		if (marks.stripTrailingZeros().scale() > 2) {
			return "Marks can have at most 2 decimal places";
		}
		return null;
	}

	private static void checkContent(Sanitized sanitized, String field, String label, List<FieldError> errors,
			Map<String, Set<UUID>> mediaByField) {
		if (sanitized.problem() != null) {
			errors.add(new FieldError(field, sanitized.problem()));
		}
		else if (!sanitized.nonEmpty()) {
			errors.add(new FieldError(field, label + " must not be empty"));
		}
		if (!sanitized.mediaIds().isEmpty()) {
			mediaByField.put(field, sanitized.mediaIds());
		}
	}

}
