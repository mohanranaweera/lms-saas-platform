package com.lms.paymentmanagement.slip.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Mockito-free unit coverage for {@link SlipContentSniffer} (package-private,
 * hence this test lives in the same package). Confirms every accepted
 * magic-byte signature (PDF/PNG/JPEG/GIF87a/GIF89a) is sniffed correctly, and
 * that a disguised/garbage byte sequence AND a plain-text file are both
 * rejected - unlike {@code content-management}'s {@code ContentSniffer}
 * (MVP-009), this sniffer deliberately has no "notes"/plain-text branch,
 * since spec 08 describes only "slip image/PDF" as the accepted upload shape
 * (see the class's own javadoc).
 */
class SlipContentSnifferTest {

	@Test
	void sniffsAValidPdfAsApplicationPdf() {
		byte[] bytes = "%PDF-1.4\n%Fixture PDF content.\n%%EOF".getBytes(StandardCharsets.US_ASCII);

		assertThat(SlipContentSniffer.sniff(bytes)).isEqualTo("application/pdf");
	}

	@Test
	void sniffsAValidPngAsImagePng() {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		byte[] bytes = withPadding(signature);

		assertThat(SlipContentSniffer.sniff(bytes)).isEqualTo("image/png");
	}

	@Test
	void sniffsAValidJpegAsImageJpeg() {
		byte[] signature = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
		byte[] bytes = withPadding(signature);

		assertThat(SlipContentSniffer.sniff(bytes)).isEqualTo("image/jpeg");
	}

	@Test
	void sniffsAValidGif87aAsImageGif() {
		byte[] signature = { 'G', 'I', 'F', '8', '7', 'a' };
		byte[] bytes = withPadding(signature);

		assertThat(SlipContentSniffer.sniff(bytes)).isEqualTo("image/gif");
	}

	@Test
	void sniffsAValidGif89aAsImageGif() {
		byte[] signature = { 'G', 'I', 'F', '8', '9', 'a' };
		byte[] bytes = withPadding(signature);

		assertThat(SlipContentSniffer.sniff(bytes)).isEqualTo("image/gif");
	}

	@Test
	void rejectsGarbageBytesMatchingNoSignature() {
		byte[] bytes = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

		assertThat(SlipContentSniffer.sniff(bytes)).isNull();
	}

	@Test
	void rejectsAnMzHeaderedExecutableDisguisedAsAPdf() {
		byte[] bytes = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00 };

		assertThat(SlipContentSniffer.sniff(bytes)).isNull();
	}

	/**
	 * Deliberately, unlike Module 9's {@code ContentSniffer}, there is no
	 * plain-text/"notes" branch here - a plain-text file must be rejected,
	 * not silently accepted as an evidence document.
	 */
	@Test
	void rejectsAPlainTextFile() {
		byte[] bytes = "This is a plain-text file, not a PDF or image.".getBytes(StandardCharsets.UTF_8);

		assertThat(SlipContentSniffer.sniff(bytes)).isNull();
	}

	@Test
	void rejectsEmptyBytes() {
		assertThat(SlipContentSniffer.sniff(new byte[0])).isNull();
	}

	@Test
	void rejectsBytesShorterThanTheShortestSignature() {
		byte[] bytes = { (byte) 0x89, 'P' };

		assertThat(SlipContentSniffer.sniff(bytes)).isNull();
	}

	private static byte[] withPadding(byte[] signature) {
		byte[] padding = "fixture-padding-bytes".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[signature.length + padding.length];
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		System.arraycopy(padding, 0, bytes, signature.length, padding.length);
		return bytes;
	}

}
