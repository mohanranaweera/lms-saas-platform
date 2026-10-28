package com.lms.contentmanagement.library.service;

import com.lms.contentmanagement.api.TeacherProfileMediaApi;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.integrationmanagement.api.DownloadOverrides;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TeacherProfileMediaApiImpl implements TeacherProfileMediaApi {

	private final MediaFileRepository mediaFileRepository;
	private final ObjectStorageApi objectStorageApi;

	public TeacherProfileMediaApiImpl(MediaFileRepository mediaFileRepository, ObjectStorageApi objectStorageApi) {
		this.mediaFileRepository = mediaFileRepository;
		this.objectStorageApi = objectStorageApi;
	}

	@Override
	public Optional<ProfileImage> findLiveImage(UUID mediaFileId) {
		if (mediaFileId == null) {
			return Optional.empty();
		}
		return mediaFileRepository.findLiveById(mediaFileId)
			.filter(file -> file.getKind() == MediaFileKind.IMAGE)
			.map(file -> new ProfileImage(file.getId(), objectStorageApi.generateSignedDownloadUrl(file.getObjectKey(),
					Duration.ofMinutes(5), DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename())).url()));
	}
}
