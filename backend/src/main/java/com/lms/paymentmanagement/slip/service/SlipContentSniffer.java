package com.lms.paymentmanagement.slip.service;

/**
 * Minimal, dependency-free magic-byte content-type detection for manual
 * payment slip uploads - a local duplicate of {@code content-management}'s
 * {@code ContentSniffer} signature table (that class is package-private to
 * {@code content-management.material.service} and not exported via any
 * {@code api} package, so importing it directly would itself be a module-
 * boundary violation per {@code .claude/rules/architecture.md}; see plan §21
 * item 8). Deliberately narrower than the material allow-list: PDF/image
 * signatures only, no plain-text "notes" branch, since spec 08 describes
 * only "slip image/PDF" as the accepted upload shape - never trusts a
 * client-declared {@code Content-Type} or file extension.
 */
final class SlipContentSniffer {

	private static final byte[] PDF_SIGNATURE = { '%', 'P', 'D', 'F', '-' };

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final byte[] GIF87_SIGNATURE = { 'G', 'I', 'F', '8', '7', 'a' };

	private static final byte[] GIF89_SIGNATURE = { 'G', 'I', 'F', '8', '9', 'a' };

	private SlipContentSniffer() {
	}

	/**
	 * @return the sniffed canonical MIME type for {@code bytes}, or {@code
	 * null} if the bytes do not match any allow-listed signature (i.e. the
	 * file must be rejected - a {@code null} return is never itself an
	 * accept decision).
	 */
	static String sniff(byte[] bytes) {
		if (matches(bytes, PDF_SIGNATURE)) {
			return "application/pdf";
		}
		if (matches(bytes, PNG_SIGNATURE)) {
			return "image/png";
		}
		if (matches(bytes, JPEG_SIGNATURE)) {
			return "image/jpeg";
		}
		if (matches(bytes, GIF87_SIGNATURE) || matches(bytes, GIF89_SIGNATURE)) {
			return "image/gif";
		}
		return null;
	}

	private static boolean matches(byte[] bytes, byte[] signature) {
		if (bytes.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (bytes[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

}
