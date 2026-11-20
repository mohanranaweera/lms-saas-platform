package com.lms.exammanagement.service.questionbank;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Server-side sanitizer for question / option / explanation rich content (contract
 * {@code docs/api/exam-management-v2.md} "Rich content format"). Pure (no Spring, no I/O):
 * media-id existence is checked by the caller against {@code ExamQuestionMediaApi}.
 *
 * <p>Allowed: {@code p, br, strong, b, em, i, u, s, code, pre, h1, h2, ul, ol, li,
 * blockquote}, {@code img[data-media-id]}, {@code span[data-math=inline][data-latex]},
 * {@code div[data-math=block][data-latex]}. Everything else is removed by jsoup's
 * {@link Cleaner} (tags dropped keeping their text; every attribute not listed - {@code
 * style}, {@code on*}, {@code href}, {@code src}, {@code class}... - stripped), so {@code
 * javascript:} URLs cannot survive anywhere. After cleaning:
 * <ul>
 * <li>an {@code img} without a {@code data-media-id} is removed; a malformed id is
 * reported (400); the id is re-written in canonical UUID form;</li>
 * <li>a {@code span}/{@code div} that is not a well-formed math node is unwrapped (its
 * text kept); a math node's children are dropped - KaTeX renders it client-side from
 * {@code data-latex} alone.</li>
 * </ul>
 */
public final class RichContentSanitizer {

	/** Maximum stored (sanitized) HTML length per field. */
	public static final int MAX_HTML_LENGTH = 20_000;

	/** Maximum length of one math node's LaTeX source. */
	public static final int MAX_LATEX_LENGTH = 2_000;

	private static final String MEDIA_ATTR = "data-media-id";

	private static final String MATH_ATTR = "data-math";

	private static final String LATEX_ATTR = "data-latex";

	private static final Safelist SAFELIST = new Safelist()
		.addTags("p", "br", "strong", "b", "em", "i", "u", "s", "code", "pre", "h1", "h2", "ul", "ol", "li",
				"blockquote", "img", "span", "div")
		.addAttributes("img", MEDIA_ATTR)
		.addAttributes("span", MATH_ATTR, LATEX_ATTR)
		.addAttributes("div", MATH_ATTR, LATEX_ATTR);

	private RichContentSanitizer() {
	}

	/**
	 * @param html sanitized HTML ({@code ""} when nothing survived)
	 * @param nonEmpty visible text, at least one image, or at least one math node
	 * @param mediaIds every {@code data-media-id} referenced (canonical, in order)
	 * @param problem a client-facing validation message, or {@code null} when valid
	 */
	public record Sanitized(String html, boolean nonEmpty, Set<UUID> mediaIds, String problem) {
	}

	public static Sanitized sanitize(String raw) {
		Document dirty = Jsoup.parseBodyFragment(raw == null ? "" : raw);
		Document clean = new Cleaner(SAFELIST).clean(dirty);
		configure(clean);
		Element body = clean.body();
		String problem = null;
		Set<UUID> mediaIds = new LinkedHashSet<>();

		boolean hasMath = false;
		for (Element element : body.select("span, div")) {
			if (element.ownerDocument() == null) {
				continue; // already dropped together with an enclosing math node
			}
			String math = element.attr(MATH_ATTR);
			String latex = element.attr(LATEX_ATTR);
			boolean wellFormed = (("span".equals(element.tagName()) && "inline".equals(math))
					|| ("div".equals(element.tagName()) && "block".equals(math))) && !latex.isBlank();
			if (!wellFormed) {
				element.unwrap();
				continue;
			}
			if (latex.length() > MAX_LATEX_LENGTH) {
				problem = "A math formula must be at most " + MAX_LATEX_LENGTH + " characters";
			}
			element.empty();
			element.clearAttributes();
			element.attr(MATH_ATTR, math);
			element.attr(LATEX_ATTR, latex);
			hasMath = true;
		}

		for (Element img : body.select("img")) {
			if (img.ownerDocument() == null) {
				continue;
			}
			String value = img.attr(MEDIA_ATTR).strip();
			img.clearAttributes();
			if (value.isEmpty()) {
				img.remove();
				continue;
			}
			UUID id = parseUuid(value);
			if (id == null) {
				problem = "An image reference is not valid";
				img.remove();
				continue;
			}
			img.attr(MEDIA_ATTR, id.toString());
			mediaIds.add(id);
		}

		String text = body.text().replace((char) 0xA0, ' ').strip();
		boolean nonEmpty = !text.isEmpty() || !mediaIds.isEmpty() || hasMath;
		String html = body.html();
		if (html.length() > MAX_HTML_LENGTH && problem == null) {
			problem = "Content must be at most " + MAX_HTML_LENGTH + " characters";
		}
		return new Sanitized(html, nonEmpty, mediaIds, problem);
	}

	/** Every canonical {@code data-media-id} in already-stored (sanitized) HTML. */
	public static Set<UUID> mediaIdsIn(String storedHtml) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (storedHtml == null || !storedHtml.contains(MEDIA_ATTR)) {
			return ids;
		}
		for (Element img : Jsoup.parseBodyFragment(storedHtml).body().select("img[" + MEDIA_ATTR + "]")) {
			UUID id = parseUuid(img.attr(MEDIA_ATTR));
			if (id != null) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Adds {@code src} (a short-lived signed URL) to every stored image for an authorized
	 * read. Images whose URL is unavailable stay without {@code src}. Never persisted.
	 */
	public static String withSignedSources(String storedHtml, Map<UUID, String> signedUrls) {
		if (storedHtml == null || !storedHtml.contains(MEDIA_ATTR)) {
			return storedHtml;
		}
		Document document = Jsoup.parseBodyFragment(storedHtml);
		configure(document);
		for (Element img : document.body().select("img[" + MEDIA_ATTR + "]")) {
			UUID id = parseUuid(img.attr(MEDIA_ATTR));
			String url = id == null ? null : signedUrls.get(id);
			if (url != null) {
				img.attr("src", url);
			}
		}
		return document.body().html();
	}

	/** Plain text (a CSV cell) as one escaped paragraph; line breaks become {@code <br>}. */
	public static String plainTextToHtml(String text) {
		String[] lines = text.strip().split("\\r\\n|\\r|\\n", -1);
		StringBuilder html = new StringBuilder("<p>");
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				html.append("<br>");
			}
			html.append(escape(lines[i]));
		}
		return html.append("</p>").toString();
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private static void configure(Document document) {
		document.outputSettings()
			.prettyPrint(false)
			.charset(StandardCharsets.UTF_8)
			.escapeMode(Entities.EscapeMode.base);
	}

	private static UUID parseUuid(String value) {
		try {
			UUID id = UUID.fromString(value.strip());
			return id.toString().equalsIgnoreCase(value.strip()) ? id : null;
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
