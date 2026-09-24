package com.lms.videoaccessmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link VideoAssetStatus} to/from V51's {@code ck_video_asset_status}
 * CHECK constraint values, mirroring {@code
 * contentmanagement.material.domain.MaterialTypeConverter}'s shape exactly.
 */
@Converter(autoApply = true)
public class VideoAssetStatusConverter implements AttributeConverter<VideoAssetStatus, String> {

	@Override
	public String convertToDatabaseColumn(VideoAssetStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public VideoAssetStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : VideoAssetStatus.valueOf(dbData);
	}

}
