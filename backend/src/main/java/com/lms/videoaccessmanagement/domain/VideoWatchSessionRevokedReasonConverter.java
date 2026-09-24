package com.lms.videoaccessmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mirrors {@link VideoAssetStatusConverter}'s shape exactly, for {@link VideoWatchSessionRevokedReason}. */
@Converter(autoApply = true)
public class VideoWatchSessionRevokedReasonConverter
		implements AttributeConverter<VideoWatchSessionRevokedReason, String> {

	@Override
	public String convertToDatabaseColumn(VideoWatchSessionRevokedReason attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public VideoWatchSessionRevokedReason convertToEntityAttribute(String dbData) {
		return dbData == null ? null : VideoWatchSessionRevokedReason.valueOf(dbData);
	}

}
