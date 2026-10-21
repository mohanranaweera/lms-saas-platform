package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.FieldValidationException;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * KLASS-T4a pure-rule unit coverage: lesson validation codes, tag
 * normalization/dedupe, slug derivation, invite-link validation, cover
 * content sniffing and the create-form pricing-model whitelist.
 */
class CourseKlassRulesTest {

	private static final Instant START = Instant.parse("2026-10-07T16:20:00Z");

	private static LessonInput lesson(Instant start, Instant end, LessonResourceType type, LessonPaymentType payment,
			BigDecimal price, Integer year, Integer month) {
		return new LessonInput("Session", null, start, end, type, payment, price, null, null, year, month);
	}

	private static String codeOf(Runnable action) {
		try {
			action.run();
		}
		catch (FieldValidationException ex) {
			return ex.getErrorCode();
		}
		return null;
	}

	@Test
	void lessonEndingAtOrBeforeItsStartIsRejectedWithEndsBeforeStart() {
		assertThat(codeOf(() -> CourseLessonService.validate(lesson(START, START, null, null, null, null, null))))
			.isEqualTo("ENDS_BEFORE_START");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(START, START.minusSeconds(60), null, null, null, null, null)))).isEqualTo("ENDS_BEFORE_START");
		assertThat(CourseLessonService.validate(lesson(START, START.plusSeconds(60), null, null, null, null, null)))
			.isEqualTo("Session");
	}

	@Test
	void paidLessonNeedsAPositivePriceAndOtherTypesMustNotCarryOne() {
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, null, LessonPaymentType.PAID, null, null, null))))
			.isEqualTo("PRICE_REQUIRED_FOR_PAID");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, null, LessonPaymentType.PAID, BigDecimal.ZERO, null, null))))
			.isEqualTo("PRICE_REQUIRED_FOR_PAID");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, null, LessonPaymentType.FREE, new BigDecimal("10.00"), null, null))))
			.isEqualTo("PRICE_NOT_ALLOWED");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, null, null, new BigDecimal("10.00"), null, null))))
			.isEqualTo("PRICE_NOT_ALLOWED");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, null, LessonPaymentType.PAID, new BigDecimal("1500.00"), null, null))))
			.isNull();
	}

	@Test
	void unsupportedResourceTypeIsRejectedButLinkOnlyAndIntegratedTypesAreAccepted() {
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, LessonResourceType.ZOOM_RECORDING, null, null, null, null))))
			.isEqualTo("RESOURCE_TYPE_UNSUPPORTED");
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, LessonResourceType.VIDEO_LINK, null, null, null, null)))).isNull();
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, LessonResourceType.SECURE_VIDEO, null, null, null, null)))).isNull();
		assertThat(codeOf(() -> CourseLessonService
			.validate(lesson(null, null, LessonResourceType.IMAGE, null, null, null, null)))).isNull();
	}

	@Test
	void blankLessonTitleIsRejected() {
		assertThatThrownBy(() -> CourseLessonService
			.validate(new LessonInput("   ", null, null, null, null, null, null, null, null, null, null)))
			.isInstanceOf(FieldValidationException.class);
	}

	@Test
	void tagNamesAreTrimmedWhitespaceCollapsedAndDedupedCaseInsensitivelyKeepingTheFirstSpelling() {
		Map<String, String> normalized = CourseRelationsService
			.normalizeTagNames(Arrays.asList(" Physics ", "physics", "Combined   Maths", "", "   ", "PHYSICS",
					"combined maths", "2028 A/L"));

		assertThat(normalized).containsExactly(Map.entry("physics", "Physics"),
				Map.entry("combined maths", "Combined Maths"), Map.entry("2028 a/l", "2028 A/L"));
	}

	@Test
	void overlongTagNameIsRejected() {
		assertThatThrownBy(() -> CourseRelationsService.normalizeTagNames(List.of("x".repeat(101))))
			.isInstanceOf(FieldValidationException.class);
	}

	@Test
	void slugIsDerivedFromTheNameAsLowercaseAsciiWithSingleHyphens() {
		assertThat(CourseService.slugify("2027 A/L Biology Theory - Live Online Class"))
			.isEqualTo("2027-a-l-biology-theory-live-online-class");
		assertThat(CourseService.slugify("  Café   Ünïcode!! ")).isEqualTo("cafe-unicode");
		assertThat(CourseService.slugify("!!!")).isEqualTo("course");
		assertThat(CourseService.slugify("a".repeat(300))).hasSizeLessThanOrEqualTo(120);
	}

	@Test
	void inviteLinksMustBeHttpsWithAHost() {
		assertThat(CourseService.cleanInviteUrl("  https://chat.whatsapp.com/abc ", "whatsappInviteUrl"))
			.isEqualTo("https://chat.whatsapp.com/abc");
		assertThat(CourseService.cleanInviteUrl("", "x")).isNull();
		assertThat(CourseService.cleanInviteUrl(null, "x")).isNull();
		assertThatThrownBy(() -> CourseService.cleanInviteUrl("http://t.me/abc", "telegramInviteUrl"))
			.isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> CourseService.cleanInviteUrl("javascript:alert(1)", "telegramInviteUrl"))
			.isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> CourseService.cleanInviteUrl("https://", "telegramInviteUrl"))
			.isInstanceOf(FieldValidationException.class);
	}

	@Test
	void coverSnifferAcceptsOnlyJpegPngAndWebpMagicBytes() {
		assertThat(CourseCoverService.sniff(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 }))
			.isEqualTo("image/jpeg");
		assertThat(CourseCoverService.sniff(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0 }))
			.isEqualTo("image/png");
		assertThat(CourseCoverService.sniff(new byte[] { 'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P' }))
			.isEqualTo("image/webp");
		assertThat(CourseCoverService.sniff("%PDF-1.7".getBytes())).isNull();
		assertThat(CourseCoverService.sniff(new byte[] { 'G', 'I', 'F', '8', '9', 'a' })).isNull();
		assertThat(CourseCoverService.sniff(new byte[] { 'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E' }))
			.isNull();
		assertThat(CourseCoverService.sniff(new byte[0])).isNull();
	}

	@Test
	void sessionAndCustomAreNotSelectableOnCreateButTheSixKlassModelsAre() {
		assertThat(CoursePricingModel.SESSION.selectableOnCreate()).isFalse();
		assertThat(CoursePricingModel.CUSTOM.selectableOnCreate()).isFalse();
		assertThat(List.of(CoursePricingModel.FREE, CoursePricingModel.ONE_TIME, CoursePricingModel.MONTHLY,
				CoursePricingModel.MODULE, CoursePricingModel.DAILY, CoursePricingModel.INSTALLMENT))
			.allMatch(CoursePricingModel::selectableOnCreate);
	}

}
