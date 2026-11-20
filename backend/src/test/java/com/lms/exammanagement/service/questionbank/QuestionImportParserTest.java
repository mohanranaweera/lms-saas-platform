package com.lms.exammanagement.service.questionbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.exammanagement.service.questionbank.QuestionBankViews.ImportError;
import com.lms.exammanagement.service.questionbank.QuestionImportService.ParsedQuestion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionImportParserTest {

	@Test
	void parsesTheKlassTemplateWithQuotedCommasNewlinesAndUnicode() {
		String csv = "question, correct answer, answer 2, answer 3 , answer 4\r\n"
				+ "\"What is 1,000 + 1?\",\"1,001\",\"1,000\",,\r\n"
				+ "ශ්‍රී ලංකාවේ අගනුවර?,ශ්‍රී ජයවර්ධනපුර කෝට්ටේ,කොළඹ,மதுரை,\"multi\nline\"\r\n"
				+ ",,,,\r\n";

		List<ParsedQuestion> parsed = QuestionImportService.parse(csv);

		assertThat(parsed).hasSize(2);
		ParsedQuestion first = parsed.get(0);
		assertThat(first.bodyHtml()).isEqualTo("<p>What is 1,000 + 1?</p>");
		assertThat(first.marks()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(first.options()).hasSize(2);
		assertThat(first.options().get(0).html()).isEqualTo("<p>1,001</p>");
		assertThat(first.options().get(0).correct()).isTrue();
		assertThat(first.options().get(1).correct()).isFalse();
		ParsedQuestion second = parsed.get(1);
		assertThat(second.bodyHtml()).isEqualTo("<p>ශ්‍රී ලංකාවේ අගනුවර?</p>");
		assertThat(second.options()).extracting(QuestionContentValidator.ValidOption::html)
			.containsExactly("<p>ශ්‍රී ජයවර්ධනපුර කෝට්ටේ</p>", "<p>කොළඹ</p>", "<p>மதுரை</p>", "<p>multi<br>line</p>");
	}

	@Test
	void acceptsOptionalMarksAndExplanationColumns() {
		String csv = "Question,Correct Answer,Answer 2,Marks,Explanation\n2+2?,4,5,2.5,Because <maths>\n";

		ParsedQuestion parsed = QuestionImportService.parse(csv).get(0);

		assertThat(parsed.marks()).isEqualByComparingTo("2.50");
		assertThat(parsed.explanationHtml()).isEqualTo("<p>Because &lt;maths&gt;</p>");
	}

	@Test
	void stripsTheUtf8BomOnDecode() {
		byte[] withBom = ("﻿question,correct answer,answer 2\nq,a,b\n").getBytes(StandardCharsets.UTF_8);

		assertThat(QuestionImportService.parse(QuestionImportService.decode(withBom))).hasSize(1);
	}

	@Test
	void reportsEveryRowAndColumnProblem() {
		String csv = "question,correct answer,answer 2,marks\nq1,,b,1\nq2,a,,0\nq3,a,b,abc\n";

		assertThatThrownBy(() -> QuestionImportService.parse(csv)).isInstanceOfSatisfying(
				QuestionImportRejectedException.class,
				e -> assertThat(e.getErrors()).containsExactly(
						new ImportError(2, "correct answer", "The correct answer is required"),
						new ImportError(3, "answer 2", "At least one wrong answer (answer 2) is required"),
						new ImportError(3, "marks", "Marks must be greater than 0"),
						new ImportError(4, "marks", "Marks must be a number, for example 1 or 2.5")));
	}

	@Test
	void rejectsMissingAndUnknownHeaders() {
		assertThatThrownBy(() -> QuestionImportService.parse("question,answer 2\nq,b\n"))
			.isInstanceOfSatisfying(QuestionImportRejectedException.class,
					e -> assertThat(e.getErrors()).extracting(ImportError::column).containsExactly("correct answer"));
		assertThatThrownBy(() -> QuestionImportService.parse("question,correct answer,answer 2,answer 11\nq,a,b,c\n"))
			.isInstanceOf(QuestionImportRejectedException.class);
	}

	@Test
	void rejectsMoreThanFiveHundredRows() {
		StringBuilder csv = new StringBuilder("question,correct answer,answer 2\n");
		for (int i = 0; i < 501; i++) {
			csv.append("q").append(i).append(",a,b\n");
		}

		assertThatThrownBy(() -> QuestionImportService.parse(csv.toString()))
			.isInstanceOf(QuestionImportRejectedException.class)
			.hasFieldOrProperty("errors");
	}

	@Test
	void rejectsNonUtf8AndUnclosedQuotes() {
		assertThatThrownBy(() -> QuestionImportService.decode(new byte[] { 'q', (byte) 0xC3, (byte) 0x28 }))
			.isInstanceOf(QuestionImportRejectedException.class);
		assertThatThrownBy(() -> QuestionImportService.parse("question,correct answer,answer 2\n\"q,a,b\n"))
			.isInstanceOf(QuestionImportRejectedException.class);
	}

}
