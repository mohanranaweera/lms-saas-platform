package com.lms.videoaccessmanagement.service.player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code major.minor.patch} player versions (VIDEO Phase 8); a pre-release suffix is ignored. */
public final class PlayerVersion {

	private static final Pattern VERSION = Pattern.compile("^(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:[-+][0-9A-Za-z.-]{1,20})?$");

	private PlayerVersion() {
	}

	public static boolean isValid(String version) {
		return version != null && VERSION.matcher(version).matches();
	}

	/** True when {@code version} is well formed and not older than {@code minimum}. */
	public static boolean isAtLeast(String version, String minimum) {
		int[] actual = parse(version);
		int[] floor = parse(minimum);
		if (actual == null || floor == null) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (actual[i] != floor[i]) {
				return actual[i] > floor[i];
			}
		}
		return true;
	}

	private static int[] parse(String version) {
		if (version == null) {
			return null;
		}
		Matcher matcher = VERSION.matcher(version);
		if (!matcher.matches()) {
			return null;
		}
		return new int[] { Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
				Integer.parseInt(matcher.group(3)) };
	}

}
