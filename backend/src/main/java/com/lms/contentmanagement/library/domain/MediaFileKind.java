package com.lms.contentmanagement.library.domain;

/**
 * Library file kind (V88 {@code ck_media_file_kind}, R2-D3). Derived from the
 * server-sniffed content type - never from the client's declared type or
 * file extension.
 */
public enum MediaFileKind {

	/** PDF or UTF-8 plain text. */
	DOCUMENT,

	/** PNG, JPEG or GIF. */
	IMAGE,

	/** Reserved for future allow-listed types; nothing maps here today. */
	OTHER;

	public static MediaFileKind fromSniffedContentType(String contentType) {
		if (contentType == null) {
			return OTHER;
		}
		if (contentType.startsWith("image/")) {
			return IMAGE;
		}
		if ("application/pdf".equals(contentType) || "text/plain".equals(contentType)) {
			return DOCUMENT;
		}
		return OTHER;
	}

}
