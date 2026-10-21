package com.lms.integrationmanagement.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider-neutral response overrides for a signed download URL: the storage
 * origin serves the object with the given {@code Content-Type} and {@code
 * Content-Disposition} instead of whatever metadata was stored with it. Used
 * so staff "Open" links render inline with a forced, server-chosen content
 * type (e.g. {@code text/plain; charset=utf-8} or {@code application/pdf}),
 * and so downloads carry a sensible filename.
 *
 * <p>All values are validated/sanitised here, at construction, so no adapter
 * can be handed a header-injection payload:
 * <ul>
 * <li>{@code contentType} - optional ({@code null} = no override). Must be a
 * plain {@code type/subtype} token pair with at most a {@code charset}
 * parameter; anything else (CR/LF, quotes, extra parameters) is rejected with
 * {@link IllegalArgumentException}. Normalised to lower case.</li>
 * <li>{@code disposition} - required.</li>
 * <li>{@code filename} - optional ({@code null}/blank = no filename
 * parameter). Sanitised: control characters (incl. CR/LF), double quotes,
 * backslashes and path separators are removed, length capped. The header
 * value carries both an ASCII-only quoted {@code filename} fallback and an
 * RFC 5987 {@code filename*=UTF-8''...} parameter (RFC 6266 §4.3).</li>
 * </ul>
 */
public record DownloadOverrides(String contentType, Disposition disposition, String filename) {

	/** RFC 6266 disposition type. */
	public enum Disposition {

		INLINE("inline"), ATTACHMENT("attachment");

		private final String token;

		Disposition(String token) {
			this.token = token;
		}

		public String token() {
			return token;
		}

	}

	private static final int MAX_FILENAME_LENGTH = 200;

	private static final String TOKEN = "[a-z0-9][a-z0-9!#$&^_.+-]{0,126}";

	private static final Pattern CONTENT_TYPE = Pattern
		.compile("^" + TOKEN + "/" + TOKEN + "(;\\s?charset=[a-z0-9][a-z0-9._-]{0,39})?$");

	public DownloadOverrides {
		Objects.requireNonNull(disposition, "disposition");
		contentType = normaliseContentType(contentType);
		filename = sanitiseFilename(filename);
	}

	public static DownloadOverrides inline(String contentType, String filename) {
		return new DownloadOverrides(contentType, Disposition.INLINE, filename);
	}

	public static DownloadOverrides attachment(String contentType, String filename) {
		return new DownloadOverrides(contentType, Disposition.ATTACHMENT, filename);
	}

	/**
	 * The full {@code Content-Disposition} header value, e.g. {@code inline;
	 * filename="report.pdf"; filename*=UTF-8''report.pdf}. Never contains
	 * CR/LF or an unescaped double quote.
	 */
	public String contentDispositionHeaderValue() {
		if (filename == null) {
			return disposition.token();
		}
		return disposition.token() + "; filename=\"" + asciiFallback(filename) + "\"; filename*=UTF-8''"
				+ rfc5987Encode(filename);
	}

	private static String normaliseContentType(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalised = value.strip().toLowerCase(Locale.ROOT);
		if (!CONTENT_TYPE.matcher(normalised).matches()) {
			throw new IllegalArgumentException("Unsupported content type override");
		}
		return normalised;
	}

	private static String sanitiseFilename(String value) {
		if (value == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(value.length());
		value.codePoints().forEach(cp -> {
			if (Character.isISOControl(cp) || cp == '"' || cp == '\\' || cp == '/' || cp == 0x2028
					|| cp == 0x2029) {
				return;
			}
			out.appendCodePoint(cp);
		});
		String cleaned = out.toString().strip();
		if (cleaned.isEmpty()) {
			return null;
		}
		if (cleaned.codePointCount(0, cleaned.length()) > MAX_FILENAME_LENGTH) {
			cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_FILENAME_LENGTH));
		}
		return cleaned;
	}

	/** Printable ASCII only; anything else (and ';' / '%') becomes '_'. */
	private static String asciiFallback(String value) {
		StringBuilder out = new StringBuilder(value.length());
		value.codePoints().forEach(cp -> {
			boolean printableAscii = cp >= 0x20 && cp <= 0x7E;
			out.append(printableAscii && cp != ';' && cp != '%' ? (char) cp : '_');
		});
		return out.toString();
	}

	/** RFC 5987 ext-value encoding: attr-char kept, all other UTF-8 bytes percent-encoded. */
	private static String rfc5987Encode(String value) {
		StringBuilder out = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if (isAttrChar(c)) {
				out.append((char) c);
			}
			else {
				out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
			}
		}
		return out.toString();
	}

	private static boolean isAttrChar(int c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| "!#$&+-.^_`|~".indexOf(c) >= 0;
	}

}
