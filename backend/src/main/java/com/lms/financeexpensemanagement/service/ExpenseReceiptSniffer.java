package com.lms.financeexpensemanagement.service;

/**
 * Magic-byte content detection for expense receipt uploads (Wave 7,
 * PAR-23-05) - a local copy of {@code SlipContentSniffer}'s signature table,
 * since that class is package-private to {@code payment-management} and
 * importing it would itself be a module-boundary violation (the same reason
 * {@code SlipContentSniffer} duplicated {@code ContentSniffer}). Never trusts
 * a client-declared {@code Content-Type} or file extension. Accepts PDF, PNG
 * and JPEG only.
 */
final class ExpenseReceiptSniffer {

	private static final byte[] PDF_SIGNATURE = { '%', 'P', 'D', 'F', '-' };

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private ExpenseReceiptSniffer() {
	}

	/** @return the canonical MIME type, or {@code null} if the file must be rejected. */
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
