package com.lms.videoaccessmanagement.service.player;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * One-time pairing and launch codes (VIDEO Phase 8, ADR-025 §2): 256 bits from
 * a CSPRNG, base64url without padding (43 characters). Only
 * {@code HMAC-SHA256(PLAYER_LAUNCH_CODE_PEPPER, code)} is ever stored, so a
 * database leak exposes no usable code. A blank pepper fails closed (503).
 */
@Component
public class PlayerCodes {

	private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	private final SecureRandom random = new SecureRandom();

	private final PlayerProperties properties;

	public PlayerCodes(PlayerProperties properties) {
		this.properties = properties;
	}

	public String newCode() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Whether {@code code} has the shape {@link #newCode()} produces; anything else is never looked up. */
	public boolean isWellFormed(String code) {
		return code != null && CODE_FORMAT.matcher(code).matches();
	}

	public String hash(String code) {
		String pepper = properties.getLaunchCodePepper();
		if (!StringUtils.hasText(pepper)) {
			throw new ServiceUnavailableException("The AOC Player is not available right now. Please try again later.");
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("HmacSHA256 unavailable", impossible);
		}
	}

}
