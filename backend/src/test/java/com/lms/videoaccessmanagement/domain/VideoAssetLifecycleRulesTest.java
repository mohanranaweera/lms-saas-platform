package com.lms.videoaccessmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-domain coverage of the MediaAsset status machine (VIDEO Phase 2). */
class VideoAssetLifecycleRulesTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID USER = UUID.randomUUID();

	@Test
	void happyPathTransitionsAreAllowed() {
		assertThat(VideoAssetStatus.CREATED.canTransitionTo(VideoAssetStatus.UPLOADING)).isTrue();
		assertThat(VideoAssetStatus.UPLOADING.canTransitionTo(VideoAssetStatus.UPLOADED)).isTrue();
		assertThat(VideoAssetStatus.UPLOADED.canTransitionTo(VideoAssetStatus.PROCESSING)).isTrue();
		assertThat(VideoAssetStatus.UPLOADED.canTransitionTo(VideoAssetStatus.READY)).isTrue();
		assertThat(VideoAssetStatus.PROCESSING.canTransitionTo(VideoAssetStatus.READY)).isTrue();
		assertThat(VideoAssetStatus.FAILED.canTransitionTo(VideoAssetStatus.UPLOADING)).isTrue();
	}

	@Test
	void illegalTransitionsAreRejected() {
		assertThat(VideoAssetStatus.CREATED.canTransitionTo(VideoAssetStatus.READY)).isFalse();
		assertThat(VideoAssetStatus.READY.canTransitionTo(VideoAssetStatus.UPLOADING)).isFalse();
		assertThat(VideoAssetStatus.READY.canTransitionTo(VideoAssetStatus.PROCESSING)).isFalse();
		assertThat(VideoAssetStatus.FAILED.canTransitionTo(VideoAssetStatus.READY)).isFalse();
		for (VideoAssetStatus target : VideoAssetStatus.values()) {
			assertThat(VideoAssetStatus.DELETED.canTransitionTo(target)).isFalse();
			assertThat(target.canTransitionTo(target)).isFalse();
		}
	}

	@Test
	void everyNonDeletedStatusCanBeSoftDeleted() {
		for (VideoAssetStatus status : VideoAssetStatus.values()) {
			if (status != VideoAssetStatus.DELETED) {
				assertThat(status.canTransitionTo(VideoAssetStatus.DELETED)).as(status.name()).isTrue();
			}
		}
	}

	@Test
	void internalAssetsStartReadyAndProviderAssetsStartCreated() {
		VideoAsset internal = new VideoAsset(TENANT, "key", "a.mp4", "video/mp4", 10L, USER);
		assertThat(internal.getProvider()).isEqualTo(MediaAssetProvider.INTERNAL);
		assertThat(internal.getStatus()).isEqualTo(VideoAssetStatus.READY);

		VideoAsset youtube = youtubeAsset();
		assertThat(youtube.getProvider()).isEqualTo(MediaAssetProvider.YOUTUBE);
		assertThat(youtube.getStatus()).isEqualTo(VideoAssetStatus.CREATED);
		assertThat(youtube.getStorageObjectKey()).isNull();
	}

	@Test
	void createForProviderRefusesInternal() {
		assertThatThrownBy(() -> VideoAsset.createForProvider(TENANT, MediaAssetProvider.INTERNAL, UUID.randomUUID(), UUID.randomUUID(),
				"t", null, MediaPrivacyStatus.UNLISTED, "a.mp4", "video/mp4", 10L, USER))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transitionToEnforcesTheMachineAndFailureReasonIsKeptOnlyWhileFailed() {
		VideoAsset asset = youtubeAsset();
		assertThatThrownBy(() -> asset.transitionTo(VideoAssetStatus.READY)).isInstanceOf(IllegalStateException.class);

		asset.transitionTo(VideoAssetStatus.UPLOADING);
		asset.markFailed("network error");
		assertThat(asset.getStatus()).isEqualTo(VideoAssetStatus.FAILED);
		assertThat(asset.getProviderFailureReason()).isEqualTo("network error");

		asset.transitionTo(VideoAssetStatus.UPLOADING);
		assertThat(asset.getProviderFailureReason()).isNull();
	}

	@Test
	void deletionOnlyThroughMarkDeletedAndIsTerminal() {
		VideoAsset asset = youtubeAsset();
		assertThatThrownBy(() -> asset.transitionTo(VideoAssetStatus.DELETED))
			.isInstanceOf(IllegalStateException.class);

		Instant now = Instant.now();
		asset.markDeleted(USER, now);
		assertThat(asset.isDeleted()).isTrue();
		assertThat(asset.getDeletedBy()).isEqualTo(USER);
		assertThat(asset.getDeletedAt()).isEqualTo(now);
		assertThatThrownBy(() -> asset.markDeleted(USER, now)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> asset.transitionTo(VideoAssetStatus.UPLOADING))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void providerAssetIdIsWriteOnceAndNeverForInternal() {
		VideoAsset asset = youtubeAsset();
		asset.assignProviderAssetId("yt-1");
		asset.assignProviderAssetId("yt-1");
		assertThatThrownBy(() -> asset.assignProviderAssetId("yt-2")).isInstanceOf(IllegalStateException.class);

		VideoAsset internal = new VideoAsset(TENANT, "key", "a.mp4", "video/mp4", 10L, USER);
		assertThatThrownBy(() -> internal.assignProviderAssetId("x")).isInstanceOf(IllegalStateException.class);
	}

	private static VideoAsset youtubeAsset() {
		return VideoAsset.createForProvider(TENANT, MediaAssetProvider.YOUTUBE, UUID.randomUUID(), UUID.randomUUID(),
				"Lecture 1",
				"desc", MediaPrivacyStatus.UNLISTED, "lecture.mp4", "video/mp4", 1024L, USER);
	}

}
