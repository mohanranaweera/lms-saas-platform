package com.lms.tenantmanagement.config;

/**
 * WCAG 2.x contrast-ratio calculation (standard relative-luminance formula),
 * used by {@code ConfigPropertyRegistry}'s {@code secondary_color}
 * cross-field validator to enforce the AA minimum (4.5:1) between a
 * tenant's {@code primary_color}/{@code secondary_color} branding pair. A
 * small, pure, side-effect-free utility deliberately kept independent of
 * the registry so it can be unit-tested directly against known hex pairs.
 */
public final class WcagContrast {

	private WcagContrast() {
	}

	/**
	 * @param hex1 a {@code #RRGGBB} color, e.g. {@code "#1D4ED8"}
	 * @param hex2 a {@code #RRGGBB} color
	 * @return the WCAG contrast ratio between the two colors, always
	 * {@code >= 1.0} (order of the two arguments does not matter - the
	 * formula always divides the lighter relative luminance by the darker).
	 */
	public static double ratio(String hex1, String hex2) {
		double luminance1 = relativeLuminance(hex1);
		double luminance2 = relativeLuminance(hex2);
		double lighter = Math.max(luminance1, luminance2);
		double darker = Math.min(luminance1, luminance2);
		return (lighter + 0.05) / (darker + 0.05);
	}

	private static double relativeLuminance(String hex) {
		int r = Integer.parseInt(hex.substring(1, 3), 16);
		int g = Integer.parseInt(hex.substring(3, 5), 16);
		int b = Integer.parseInt(hex.substring(5, 7), 16);
		return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b);
	}

	private static double channelLuminance(int channel8Bit) {
		double srgb = channel8Bit / 255.0;
		return (srgb <= 0.03928) ? (srgb / 12.92) : Math.pow((srgb + 0.055) / 1.055, 2.4);
	}

}
