package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoMetricsTest {

	private SimpleMeterRegistry registry;

	private VideoMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new VideoMetrics(registry);
	}

	@Test
	void recordsBoundedPlaybackMetricsWithoutTenantOrAssetLabels() {
		metrics.playbackAuthorized("DYNTUBE");
		metrics.playbackDenied("some unexpected exception with a tenant id");

		assertThat(registry.get("video.playback.authorizations").tag("provider", "dyntube").counter().count())
			.isEqualTo(1);
		assertThat(registry.get("video.playback.denials").tag("reason", "other").counter().count()).isEqualTo(1);
		assertThat(registry.get("video.playback.denials").meter().getId().getTags())
			.allMatch(tag -> !tag.getKey().equals("tenant") && !tag.getKey().equals("asset"));
	}

	@Test
	void normalizesUnknownProviderAndOutcomeToBoundedValues() {
		metrics.upload("provider-from-request", "unbounded failure text");
		metrics.heartbeat("unexpected client state");

		assertThat(registry.get("video.media.uploads").tag("provider", "other").tag("outcome", "other")
			.counter().count()).isEqualTo(1);
		assertThat(registry.get("video.playback.heartbeats").tag("outcome", "other").counter().count())
			.isEqualTo(1);
	}

	@Test
	void recordsProviderLatencyWithBoundedOperationAndOutcomeTags() {
		io.micrometer.core.instrument.Timer.Sample sample = metrics.startProviderTimer();
		metrics.recordProviderCall(sample, "DYNTUBE", "upload", true);

		assertThat(registry.get("video.provider.latency").tag("provider", "dyntube").tag("operation", "upload")
			.tag("outcome", "success").timer().count()).isEqualTo(1);
	}

}
