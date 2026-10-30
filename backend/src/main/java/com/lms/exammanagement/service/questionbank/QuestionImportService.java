package com.lms.exammanagement.service.questionbank;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.QuestionImport;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.QuestionImportRepository;
import com.lms.exammanagement.service.questionbank.CsvReader.CsvFormatException;
import com.lms.exammanagement.service.questionbank.CsvReader.CsvRecord;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ImportError;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ImportOutcome;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.QuestionImportView;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.ValidOption;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV question import into a module bank (contract "Imports"; spec C2/QB9). Klass header
 * {@code question, correct answer, answer 2, answer 3 , answer 4} (trimmed,
 * case-insensitive), plus optional {@code answer 5..answer 10}, {@code marks} and {@code
 * explanation}. Cells are plain text, HTML-escaped into a paragraph. The correct answer is
 * stored as option 1.
 *
 * <p><b>Atomic:</b> the whole file is validated before anything is written; any problem
 * is one 422 listing every row/column problem and nothing is imported. <b>Idempotent:</b>
 * the same {@code Idempotency-Key} for the same module returns the original import (200)
 * - checked under the module-bank advisory lock, so concurrent retries cannot both import.
 */
@Service
@Transactional
public class QuestionImportService {

	public static final long MAX_FILE_BYTES = 1024L * 1024;

	public static final int MAX_DATA_ROWS = 500;

	public static final int MAX_CELL_LENGTH = 5_000;

	public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

	static final int MAX_PAGE_SIZE = 100;

	static final String COL_QUESTION = "question";

	static final String COL_CORRECT = "correct answer";

	static final String COL_MARKS = "marks";

	static final String COL_EXPLANATION = "explanation";

	private static final Pattern ANSWER_COLUMN = Pattern.compile("answer (\\d+)");

	private static final byte[] TEMPLATE = ("﻿" + "question,correct answer,answer 2,answer 3,answer 4\r\n")
		.getBytes(StandardCharsets.UTF_8);

	private final ExamQuestionRepository questionRepository;

	private final QuestionImportRepository importRepository;

	private final ModuleQuestionBankService bankService;

	private final ExamAccessGuard accessGuard;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final Clock clock;

	public QuestionImportService(ExamQuestionRepository questionRepository, QuestionImportRepository importRepository,
			ModuleQuestionBankService bankService, ExamAccessGuard accessGuard, AuditLogApi auditLogApi,
			TenantContext tenantContext, TeacherLookupApi teacherLookupApi, UserProvisioningApi userProvisioningApi,
			Clock clock) {
		this.questionRepository = questionRepository;
		this.importRepository = importRepository;
		this.bankService = bankService;
		this.accessGuard = accessGuard;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.clock = clock;
	}

	/** UTF-8 with BOM, Klass header only. */
	@Transactional(readOnly = true)
	public byte[] template() {
		accessGuard.requireAuthoringRole(PermissionAction.VIEW);
		return TEMPLATE.clone();
	}

	@Transactional(readOnly = true)
	public PageResponse<QuestionImportView> history(UUID courseId, UUID moduleId, String search, int page, int size) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		bankService.requireModule(courseId, moduleId);
		PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Page<QuestionImport> imports = importRepository.findByModuleId(moduleId, search, pageRequest);
		Map<UUID, String> names = userNames(
				imports.getContent().stream().map(QuestionImport::getImportedBy).collect(Collectors.toSet()));
		return PageResponse.from(imports.map(row -> toView(row, names)));
	}

	public ImportOutcome importCsv(UUID courseId, UUID moduleId, MultipartFile file, String rawIdempotencyKey) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);
		bankService.requireModule(courseId, moduleId);
		String idempotencyKey = validateIdempotencyKey(rawIdempotencyKey);
		bankService.lockModuleBank(moduleId);

		var previous = importRepository.findByModuleIdAndIdempotencyKey(moduleId, idempotencyKey);
		if (previous.isPresent()) {
			QuestionImport original = previous.get();
			return new ImportOutcome(toView(original, userNames(Set.of(original.getImportedBy()))), true);
		}

		if (file == null || file.isEmpty()) {
			throw QuestionImportRejectedException.fileProblem("File is required");
		}
		String fileName = sanitizeFilename(file.getOriginalFilename());
		if (!fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
			throw QuestionImportRejectedException.fileProblem("Only .csv files can be imported");
		}
		byte[] bytes = readBounded(file);
		List<ParsedQuestion> parsed = parse(decode(bytes));

		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		String sha256 = sha256Hex(bytes);
		QuestionImport saved = importRepository.save(new QuestionImport(tenantId, courseId, moduleId, fileName, sha256,
				idempotencyKey, parsed.size(), actorId, clock.instant()));

		int sequence = questionRepository.maxLiveSequence(moduleId);
		for (ParsedQuestion question : parsed) {
			ExamQuestion entity = questionRepository.save(ExamQuestion.moduleQuestion(tenantId, courseId, moduleId,
					++sequence, question.bodyHtml(), question.marks(), question.explanationHtml(), saved.getId()));
			bankService.saveNewOptions(entity.getId(), question.options());
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", courseId.toString());
		metadata.put("moduleId", moduleId.toString());
		metadata.put("fileName", fileName);
		metadata.put("fileSha256", sha256);
		metadata.put("questionCount", parsed.size());
		auditLogApi.record(new AuditLogEntry(actorId, "exam_question.imported", "question_import", saved.getId(), null,
				metadata));
		return new ImportOutcome(toView(saved, userNames(Set.of(actorId))), false);
	}

	// ------------------------------------------------------------------
	// Parsing (package-private for unit tests).
	// ------------------------------------------------------------------

	record ParsedQuestion(String bodyHtml, BigDecimal marks, String explanationHtml, List<ValidOption> options) {
	}

	static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException e) {
			throw QuestionImportRejectedException.fileProblem("The file must be saved as UTF-8 text (CSV UTF-8)");
		}
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		if (text.indexOf('\u0000') >= 0) {
			throw QuestionImportRejectedException.fileProblem("The file is not a text CSV file");
		}
		return text;
	}

	static List<ParsedQuestion> parse(String text) {
		List<CsvRecord> records;
		try {
			records = CsvReader.read(text);
		}
		catch (CsvFormatException e) {
			throw new QuestionImportRejectedException(List.of(new ImportError(e.recordNumber(), null, e.getMessage())));
		}
		if (records.isEmpty() || records.get(0).isBlank()) {
			throw QuestionImportRejectedException.fileProblem("The file is empty - it needs the template header row");
		}

		// Header.
		List<ImportError> errors = new ArrayList<>();
		CsvRecord header = records.get(0);
		Map<String, Integer> columns = new LinkedHashMap<>();
		List<String> headerNames = new ArrayList<>();
		for (int i = 0; i < header.cells().size(); i++) {
			String name = normalizeHeader(header.cells().get(i));
			headerNames.add(name);
			if (name.isEmpty()) {
				continue;
			}
			if (!isKnownColumn(name)) {
				errors.add(new ImportError(1, header.cells().get(i).strip(), "Unknown column \"" + header.cells().get(i).strip()
						+ "\" - use the template columns: question, correct answer, answer 2 ... answer 10, marks, explanation"));
				continue;
			}
			if (columns.putIfAbsent(name, i) != null) {
				errors.add(new ImportError(1, name, "The column \"" + name + "\" appears more than once"));
			}
		}
		for (String required : List.of(COL_QUESTION, COL_CORRECT, "answer 2")) {
			if (!columns.containsKey(required)) {
				errors.add(new ImportError(1, required, "The required column \"" + required + "\" is missing"));
			}
		}
		if (!errors.isEmpty()) {
			throw new QuestionImportRejectedException(errors);
		}

		List<CsvRecord> dataRows = records.subList(1, records.size()).stream().filter(row -> !row.isBlank()).toList();
		if (dataRows.isEmpty()) {
			throw QuestionImportRejectedException.fileProblem("The file has no questions");
		}
		if (dataRows.size() > MAX_DATA_ROWS) {
			throw QuestionImportRejectedException
				.fileProblem("The file has " + dataRows.size() + " questions - at most " + MAX_DATA_ROWS
						+ " can be imported at once");
		}

		List<ParsedQuestion> questions = new ArrayList<>();
		for (CsvRecord row : dataRows) {
			int rowNumber = row.number();
			List<String> cells = row.cells();
			for (int i = 0; i < cells.size(); i++) {
				boolean unnamed = i >= headerNames.size() || headerNames.get(i).isEmpty();
				if (unnamed && !cells.get(i).isBlank()) {
					errors.add(new ImportError(rowNumber, null, "Cell " + (i + 1) + " has no column header"));
				}
			}
			String question = cell(cells, columns, COL_QUESTION);
			String correct = cell(cells, columns, COL_CORRECT);
			requireText(question, rowNumber, COL_QUESTION, "The question is required", errors);
			requireText(correct, rowNumber, COL_CORRECT, "The correct answer is required", errors);
			String second = cell(cells, columns, "answer 2");
			requireText(second, rowNumber, "answer 2", "At least one wrong answer (answer 2) is required", errors);

			List<ValidOption> options = new ArrayList<>();
			options.add(new ValidOption(null, RichContentSanitizer.plainTextToHtml(correct), true));
			options.add(new ValidOption(null, RichContentSanitizer.plainTextToHtml(second), false));
			for (int n = 3; n <= QuestionContentValidator.MAX_OPTIONS; n++) {
				String column = "answer " + n;
				String value = cell(cells, columns, column);
				if (!value.isBlank()) {
					checkLength(value, rowNumber, column, errors);
					options.add(new ValidOption(null, RichContentSanitizer.plainTextToHtml(value), false));
				}
			}

			BigDecimal marks = BigDecimal.ONE;
			String rawMarks = cell(cells, columns, COL_MARKS).strip();
			if (!rawMarks.isEmpty()) {
				try {
					marks = new BigDecimal(rawMarks);
					String problem = QuestionContentValidator.marksProblem(marks);
					if (problem != null) {
						errors.add(new ImportError(rowNumber, COL_MARKS, problem));
					}
				}
				catch (NumberFormatException e) {
					errors.add(new ImportError(rowNumber, COL_MARKS, "Marks must be a number, for example 1 or 2.5"));
				}
			}
			String explanation = cell(cells, columns, COL_EXPLANATION);
			checkLength(explanation, rowNumber, COL_EXPLANATION, errors);

			if (errors.isEmpty()) {
				questions.add(new ParsedQuestion(RichContentSanitizer.plainTextToHtml(question), marks.setScale(2),
						explanation.isBlank() ? null : RichContentSanitizer.plainTextToHtml(explanation),
						List.copyOf(options)));
			}
		}
		if (!errors.isEmpty()) {
			throw new QuestionImportRejectedException(errors);
		}
		return questions;
	}

	private static String cell(List<String> cells, Map<String, Integer> columns, String column) {
		Integer index = columns.get(column);
		if (index == null || index >= cells.size()) {
			return "";
		}
		return cells.get(index);
	}

	private static void requireText(String value, int row, String column, String message, List<ImportError> errors) {
		if (value.isBlank()) {
			errors.add(new ImportError(row, column, message));
			return;
		}
		checkLength(value, row, column, errors);
	}

	private static void checkLength(String value, int row, String column, List<ImportError> errors) {
		if (value.strip().length() > MAX_CELL_LENGTH) {
			errors.add(new ImportError(row, column, "Must be at most " + MAX_CELL_LENGTH + " characters"));
		}
	}

	static String normalizeHeader(String raw) {
		return raw.replace('﻿', ' ').strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static boolean isKnownColumn(String name) {
		if (COL_QUESTION.equals(name) || COL_CORRECT.equals(name) || COL_MARKS.equals(name)
				|| COL_EXPLANATION.equals(name)) {
			return true;
		}
		Matcher matcher = ANSWER_COLUMN.matcher(name);
		if (!matcher.matches() || matcher.group(1).length() > 2) {
			return false;
		}
		int n = Integer.parseInt(matcher.group(1));
		return n >= 2 && n <= QuestionContentValidator.MAX_OPTIONS && name.equals("answer " + n);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private static String validateIdempotencyKey(String raw) {
		String key = raw == null ? "" : raw.strip();
		String problem = null;
		if (key.isEmpty()) {
			problem = "The Idempotency-Key header is required";
		}
		else if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			problem = "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters";
		}
		else if (key.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
			problem = "Idempotency-Key must not contain control characters";
		}
		if (problem != null) {
			throw new FieldValidationException(problem, List.of(new FieldError("Idempotency-Key", problem)));
		}
		return key;
	}

	private QuestionImportView toView(QuestionImport row, Map<UUID, String> names) {
		return new QuestionImportView(row.getId(), row.getFileName(), row.getImportedAt(), row.getQuestionCount(),
				names.get(row.getImportedBy()));
	}

	/** Teacher display names, falling back to the account email for other staff (staff-only endpoint). */
	private Map<UUID, String> userNames(Set<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> userIds.contains(teacher.userId()) && teacher.name() != null
					&& !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		List<UUID> missing = userIds.stream().filter(id -> !names.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(missing)) {
				if (summary.email() != null) {
					names.putIfAbsent(summary.userId(), summary.email());
				}
			}
		}
		return names;
	}

	static String sanitizeFilename(String rawFilename) {
		String name = rawFilename == null ? "questions.csv" : rawFilename;
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		name = name.chars()
			.filter(c -> c >= 0x20 && c != 0x7F)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString()
			.strip();
		if (name.isEmpty()) {
			name = "questions.csv";
		}
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

	private static byte[] readBounded(MultipartFile file) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > MAX_FILE_BYTES) {
					throw QuestionImportRejectedException.fileProblem("The file is larger than 1 MB");
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read the uploaded CSV file", e);
		}
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
