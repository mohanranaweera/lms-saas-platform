package com.lms.videoaccessmanagement.service;

import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency-free magic-byte content-type detection for the video
 * upload allow-list (mp4/webm/quicktime only - Wave 5, plan §3/§4).
 * Deliberately does NOT trust a client-declared {@code Content-Type} or file
 * extension - {@code .claude/rules/security.md} requires detection on the
 * actual byte stream, mirroring {@code
 * contentmanagement.material.service.ContentSniffer}'s established technique
 * exactly.
 *
 * <p><b>Deliberate duplication, not an extension of {@code ContentSniffer} -
 * documented design choice:</b> the task brief for this module floated
 * either extending the existing {@code ContentSniffer} with a video overload,
 * or writing a small video-specific sniffer inside {@code
 * video-access-management} itself, and asked to pick whichever keeps the
 * architecture boundary clean. {@code ContentSniffer} is a {@code
 * content-management}-owned class living in that module's own {@code
 * material.service} package (not {@code content-management}'s {@code api}
 * package) - {@code video-access-management} must never import another
 * domain's non-{@code api} classes, per {@code
 * .claude/rules/architecture.md}, so extending it in place is not an option
 * without either promoting it into an {@code api} package (a larger, more
 * invasive change to a class this wave doesn't otherwise need to touch) or
 * accepting a boundary violation. A small, self-contained, side-effect-free
 * duplicate kept local to this module is the cleaner choice, mirroring the
 * identical duplication decision made for {@code
 * support.RequestDeviceFingerprint} (see that class's javadoc).
 *
 * <p>MP4 and QuickTime (.mov) both use the ISO base media container's {@code
 * ftyp} box at byte offset 4 - they are distinguished by the box's "major
 * brand" field at offset 8: a {@code qt} brand is QuickTime, every other
 * recognized brand is treated as MP4. WebM uses the unrelated Matroska/EBML
 * magic signature at offset 0.
 */
public final class VideoContentSniffer {

	private static final byte[] EBML_SIGNATURE = { (byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3 };

	private static final byte[] FTYP_BOX_TYPE = "ftyp".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] QUICKTIME_BRAND = "qt".getBytes(StandardCharsets.US_ASCII);

	private static final int FTYP_BOX_TYPE_OFFSET = 4;

	private static final int MAJOR_BRAND_OFFSET = 8;

	private VideoContentSniffer() {
	}

	/**
	 * @return the sniffed canonical MIME type for {@code bytes}, or {@code
	 * null} if the bytes do not match any allow-listed video signature (i.e.
	 * the upload must be rejected - a null return is never itself an accept
	 * decision).
	 */
	public static String sniff(byte[] bytes) {
		if (matchesAt(bytes, 0, EBML_SIGNATURE)) {
			return "video/webm";
		}
		if (matchesAt(bytes, FTYP_BOX_TYPE_OFFSET, FTYP_BOX_TYPE)) {
			if (matchesAt(bytes, MAJOR_BRAND_OFFSET, QUICKTIME_BRAND)) {
				return "video/quicktime";
			}
			return "video/mp4";
		}
		return null;
	}

	private static boolean matchesAt(byte[] bytes, int offset, byte[] signature) {
		if (bytes.length < offset + signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (bytes[offset + i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

}
