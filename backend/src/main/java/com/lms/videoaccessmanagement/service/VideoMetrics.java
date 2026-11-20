package com.lms.videoaccessmanagement.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Bounded operational metrics for video access and player traffic.
 *
 * <p>Metric tags intentionally describe provider, operation and bounded outcome
 * categories only. Tenant ids, asset ids, session ids and user-controlled
 * values must never become metric labels because their cardinality is unbounded.
 */
@Component
public class VideoMetrics {

	private final MeterRegistry registry;

	public VideoMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public void playbackAuthorized(String provider) {
		counter("video.playback.authorizations", "provider", boundedProvider(provider)).increment();
	}

	public void playbackDenied(String reason) {
		counter("video.playback.denials", "reason", boundedReason(reason)).increment();
	}

	public void playerExchangeSucceeded(String provider) {
		counter("video.player.exchanges", "outcome", "success", "provider", boundedProvider(provider)).increment();
	}

	public void playerExchangeDenied(String reason) {
		counter("video.player.exchanges", "outcome", "denied", "reason", boundedReason(reason)).increment();
	}

	public void heartbeat(String outcome) {
		counter("video.playback.heartbeats", "outcome", boundedOutcome(outcome)).increment();
	}

	public Timer.Sample startProviderTimer() {
		return Timer.start(registry);
	}

	public void recordProviderCall(Timer.Sample sample, String provider, String operation, boolean success) {
		if (sample == null) {
			return;
		}
		sample.stop(Timer.builder("video.provider.latency")
			.description("Latency of calls to video providers")
			.tags("provider", boundedProvider(provider), "operation", boundedOperation(operation), "outcome",
					success ? "success" : "failure")
			.register(registry));
	}

	public void upload(String provider, String outcome) {
		counter("video.media.uploads", "provider", boundedProvider(provider), "outcome", boundedOutcome(outcome))
			.increment();
	}

	public void uploadFailure(String provider, String reason) {
		counter("video.media.upload.failures", "provider", boundedProvider(provider), "reason", boundedReason(reason))
			.increment();
	}

	private Counter counter(String name, String... tags) {
		return Counter.builder(name).tags(tags).register(registry);
	}

	private static String boundedProvider(String value) {
		if (value == null) {
			return "unknown";
		}
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "INTERNAL", "YOUTUBE", "DYNTUBE", "BUNNY", "SELF_HOSTED" -> value.toLowerCase();
			default -> "other";
		};
	}

	private static String boundedReason(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		return switch (normalized) {
			case "NATIVE_PLAYER_UNAVAILABLE", "VIDEO_PLAYBACK_DISABLED", "PLAYBACK_WATCH_LIMIT",
					"PLAYBACK_LAUNCH_CODE_INVALID", "PLAYER_RATE_LIMITED", "PLAYBACK_SESSION_EXPIRED",
					"ACCESS_REVOKED", "WATCH_TIME_EXHAUSTED", "PROVIDER_UNAVAILABLE", "AUTH_EXPIRED" -> normalized;
			default -> "other";
		};
	}

	private static String boundedOutcome(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "SUCCESS", "CONTINUE", "STOP", "FAILURE", "DENIED", "RETRY" -> value.toLowerCase();
			default -> "other";
		};
	}

	private static String boundedOperation(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "upload", "status", "playback", "projects", "metadata" -> value.toLowerCase();
			default -> "other";
		};
	}

}
