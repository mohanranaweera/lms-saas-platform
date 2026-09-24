package com.lms.videoaccessmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Video playback JWT signing secret, sourced the same way {@code
 * integrationmanagement.config.PaymentGatewayProperties} sources {@code
 * PAYMENT_GATEWAY_WEBHOOK_SECRET}: an env-var-backed {@code
 * @ConfigurationProperties} bean with an empty-string default - never a
 * dev-only placeholder secret like {@code
 * identityaccessservice.config.JwtProperties}. An empty secret makes {@code
 * PlaybackTokenService} refuse to issue or validate any playback token
 * (fail closed, a clean 503, never a hard application-startup crash and
 * never a silently-accepted forged token) rather than falling back to any
 * default value. Real environments MUST set {@code
 * VIDEO_PLAYBACK_TOKEN_SECRET}; tests set their own fixed, clearly
 * -not-a-production-value secret via {@code application.yml} under {@code
 * src/test/resources}.
 *
 * <p>Deliberately a distinct secret from {@code
 * app.security.jwt.secret}/{@link
 * com.lms.identityaccessservice.config.JwtProperties} - not reused, not
 * derived from it - so a leaked playback token can never be replayed as a
 * login session and vice versa, even if both secrets were somehow
 * discovered together (see {@code PlaybackTokenService}'s class javadoc for
 * the full structural-isolation argument).
 */
@Component
@ConfigurationProperties(prefix = "video-playback.token")
public class VideoPlaybackTokenProperties {

	private String secret = "";

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

}
