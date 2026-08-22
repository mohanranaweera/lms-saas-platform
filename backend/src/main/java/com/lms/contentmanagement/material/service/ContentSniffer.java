package com.lms.contentmanagement.material.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency-free magic-byte content-type detection for the MVP
 * material allow-list (PDF/image/notes only - see {@code
 * docs/plans/MVP-009 Lessons and Learning Materials.md} §6/§9). Deliberately
 * does NOT trust a client-declared {@code Content-Type} or file extension -
 * {@code .claude/rules/security.md} requires detection on the actual byte
 * stream. Not a general-purpose MIME sniffer (no Apache Tika dependency
 * added - this project's {@code pom.xml} has no content-sniffing library
 * and the MVP allow-list is small/fixed enough that a hand-written
 * signature table is simpler and fully under this module's own control).
 *
 * <p>"Notes" (per the spec's literal wording "uploads PDFs/images/notes")
 * is treated as a plain-text FILE upload, detected as valid UTF-8 text
 * containing no NUL byte and no other C0 control character besides
 * tab/CR/LF - not an inline rich-text editor (plan §9's open scope
 * question, §21 item 8).
 */
public final class ContentSniffer {

	private static final byte[] PDF_SIGNATURE = { '%', 'P', 'D', 'F', '-' };

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final byte[] GIF87_SIGNATURE = "GIF87a".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] GIF89_SIGNATURE = "GIF89a".getBytes(StandardCharsets.US_ASCII);

	private ContentSniffer() {
	}

	/**
	 * @return the sniffed canonical MIME type for {@code bytes}, or {@code
	 * null} if the bytes do not match any allow-listed signature (i.e. the
	 * file must be rejected - a null return is never itself an accept
	 * decision).
	 */
	public static String sniff(byte[] bytes) {
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
		if (isPlainText(bytes)) {
			return "text/plain";
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

	/**
	 * Heuristic "notes" detection: valid UTF-8 with no NUL byte and no C0
	 * control character other than tab (0x09), LF (0x0A), CR (0x0D).
	 * Deliberately conservative - a binary file (including a renamed
	 * executable) almost never satisfies this, since executables contain
	 * NUL bytes and non-UTF-8 byte sequences within the first few hundred
	 * bytes.
	 */
	private static boolean isPlainText(byte[] bytes) {
		if (bytes.length == 0) {
			return false;
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
		decoder.onMalformedInput(CodingErrorAction.REPORT);
		decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			decoder.decode(ByteBuffer.wrap(bytes));
		}
		catch (CharacterCodingException e) {
			return false;
		}
		for (byte b : bytes) {
			int unsigned = b & 0xFF;
			if (unsigned < 0x20 && unsigned != 0x09 && unsigned != 0x0A && unsigned != 0x0D) {
				return false;
			}
		}
		return true;
	}

}
