package com.lms.exammanagement.service.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.exammanagement.service.questionbank.RichContentSanitizer.Sanitized;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RichContentSanitizerTest {

	private static final UUID MEDIA = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

	@Test
	void keepsTheAllowedFormattingTags() {
		String html = "<h1>T</h1><h2>S</h2><p><strong>b</strong><b>b</b><em>i</em><i>i</i><u>u</u><s>s</s>"
				+ "<code>c</code><br></p><pre>p</pre><ul><li>a</li></ul><ol><li>b</li></ol><blockquote>q</blockquote>";

		Sanitized result = RichContentSanitizer.sanitize(html);

		assertThat(result.problem()).isNull();
		assertThat(result.nonEmpty()).isTrue();
		assertThat(result.html()).isEqualTo(html);
	}

	@Test
	void stripsScriptsEventHandlersStylesAndJavascriptUrls() {
		String html = "<p style=\"color:red\" onclick=\"steal()\">Hi<script>alert(1)</script>"
				+ "<a href=\"javascript:alert(1)\">link</a><iframe src=\"https://evil\"></iframe></p>"
				+ "<img src=\"javascript:alert(1)\" onerror=\"alert(1)\" data-media-id=\"" + MEDIA + "\">"
				+ "<img src=\"https://evil/x.png\">";

		Sanitized result = RichContentSanitizer.sanitize(html);

		assertThat(result.html()).doesNotContain("script", "onclick", "onerror", "style", "javascript:", "href",
				"iframe", "src", "evil");
		assertThat(result.html()).isEqualTo("<p>Hilink</p><img data-media-id=\"" + MEDIA + "\">");
		assertThat(result.mediaIds()).containsExactly(MEDIA);
	}

	@Test
	void imageOnlyAndMathOnlyContentIsNonEmptyButAnEmptyParagraphIsNot() {
		assertThat(RichContentSanitizer.sanitize("<p><img data-media-id=\"" + MEDIA + "\"></p>").nonEmpty()).isTrue();
		assertThat(RichContentSanitizer.sanitize("<span data-math=\"inline\" data-latex=\"x^2\"></span>").nonEmpty())
			.isTrue();
		assertThat(RichContentSanitizer.sanitize("<div data-math=\"block\" data-latex=\"\\frac{a}{b}\">junk</div>")
			.html()).isEqualTo("<div data-math=\"block\" data-latex=\"\\frac{a}{b}\"></div>");
		assertThat(RichContentSanitizer.sanitize("<p></p>").nonEmpty()).isFalse();
		assertThat(RichContentSanitizer.sanitize("<p>&nbsp; </p>").nonEmpty()).isFalse();
		assertThat(RichContentSanitizer.sanitize("<p><script>x</script></p>").nonEmpty()).isFalse();
		assertThat(RichContentSanitizer.sanitize(null).nonEmpty()).isFalse();
	}

	@Test
	void malformedMathNodesAreUnwrappedAndMalformedImageIdsAreReported() {
		assertThat(RichContentSanitizer.sanitize("<span data-math=\"block\" data-latex=\"x\">t</span>").html())
			.isEqualTo("t");
		assertThat(RichContentSanitizer.sanitize("<span class=\"x\">t</span>").html()).isEqualTo("t");
		assertThat(RichContentSanitizer.sanitize("<img data-media-id=\"not-a-uuid\">").problem()).isNotNull();
	}

	@Test
	void unicodeTextIsPreservedUnescaped() {
		String html = "<p>ශ්‍රී ලංකාව - இலங்கை</p>";

		assertThat(RichContentSanitizer.sanitize(html).html()).isEqualTo(html);
	}

	@Test
	void signedSourcesAreAddedOnReadOnly() {
		String stored = "<p>x</p><img data-media-id=\"" + MEDIA + "\">";

		String rendered = RichContentSanitizer.withSignedSources(stored, Map.of(MEDIA, "https://signed/1?sig=a&b=c"));

		assertThat(rendered).contains("src=\"https://signed/1?sig=a&amp;b=c\"").contains("data-media-id");
		assertThat(RichContentSanitizer.mediaIdsIn(stored)).containsExactly(MEDIA);
	}

	@Test
	void plainTextBecomesAnEscapedParagraph() {
		assertThat(RichContentSanitizer.plainTextToHtml(" a < b & \"c\"\nline2 "))
			.isEqualTo("<p>a &lt; b &amp; &quot;c&quot;<br>line2</p>");
	}

}
