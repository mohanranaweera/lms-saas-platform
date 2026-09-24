package com.lms.videoaccessmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mirrors {@link VideoAssetStatusConverter}'s shape exactly, for {@link VideoWatchSessionStatus}. */
@Converter(autoApply = true)
public class VideoWatchSessionStatusConverter implements AttributeConverter<VideoWatchSessionStatus, String> {

	@Override
	public String convertToDatabaseColumn(VideoWatchSessionStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public VideoWatchSessionStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : VideoWatchSessionStatus.valueOf(dbData);
	}

}
