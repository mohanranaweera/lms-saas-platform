package com.lms.contentmanagement.material.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ContentSniffer}'s magic-byte allow-list, per
 * {@code docs/plans/MVP-009 Lessons and Learning Materials.md} §6/§9 and
 * {@code .claude/rules/security.md}'s "detect on the actual byte stream, not
 * client-declared content type/extension" requirement. No Spring context -
 * {@link ContentSniffer} is a dependency-free static utility.
 */
class ContentSnifferTest {

	@Test
	void sniffsValidPdfBytesAsApplicationPdf() {
		byte[] bytes = "%PDF-1.4\n%Fixture PDF content for MVP-009 tests.\n%%EOF".getBytes(StandardCharsets.US_ASCII);

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("application/pdf");
	}

	@Test
	void sniffsValidPngSignatureBytesAsImagePng() {
		byte[] bytes = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0x00, 0x00, 0x00, 0x0D };

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("image/png");
	}

	@Test
	void sniffsValidJpegSignatureBytesAsImageJpeg() {
		byte[] bytes = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F' };

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("image/jpeg");
	}

	@Test
	void sniffsValidGif87aSignatureBytesAsImageGif() {
		byte[] bytes = "GIF87a-fixture-payload-bytes".getBytes(StandardCharsets.US_ASCII);

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("image/gif");
	}

	@Test
	void sniffsValidGif89aSignatureBytesAsImageGif() {
		byte[] bytes = "GIF89a-fixture-payload-bytes".getBytes(StandardCharsets.US_ASCII);

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("image/gif");
	}

	@Test
	void sniffsValidUtf8PlainTextAsTextPlain() {
		byte[] bytes = "Lecture notes\nSection 1".getBytes(StandardCharsets.UTF_8);

		assertThat(ContentSniffer.sniff(bytes)).isEqualTo("text/plain");
	}

	/**
	 * The critical security case (plan §6/§9, {@code .claude/rules/security.md}):
	 * bytes starting with the Windows PE/EXE "MZ" DOS-stub magic header must be
	 * rejected even though nothing about the byte content resembles any
	 * accepted signature - this is the "renamed executable disguised as a
	 * PDF" scenario the whole module exists to catch. The NUL bytes present
	 * in a realistic DOS header also independently fail the plain-text
	 * fallback, so this must return {@code null} through both code paths.
	 */
	@Test
	void sniffRejectsBytesStartingWithTheWindowsPeExeMzMagicHeader() {
		byte[] bytes = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, (byte) 0xFF,
				(byte) 0xFF, 0x00, 0x00, (byte) 0xB8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00,
				0x00 };

		assertThat(ContentSniffer.sniff(bytes)).isNull();
	}

	@Test
	void sniffRejectsAnEmptyByteArray() {
		assertThat(ContentSniffer.sniff(new byte[0])).isNull();
	}

	@Test
	void sniffRejectsBytesThatAreNeitherARecognizedBinarySignatureNorValidUtf8Text() {
		byte[] bytes = { 0x00, 0x01, 0x02, (byte) 0xFE, (byte) 0xFF, 0x10, 0x20 };

		assertThat(ContentSniffer.sniff(bytes)).isNull();
	}

}
