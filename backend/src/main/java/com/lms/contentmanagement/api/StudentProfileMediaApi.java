package com.lms.contentmanagement.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * STU-MGMT-02 student profile photos on the shared, tenant-scoped {@code media_file}
 * store (usage {@code STUDENT_PROFILE}). These files never appear in the Media Library;
 * user-management authorizes every read and write before calling this contract.
 */
public interface StudentProfileMediaApi {

	/**
	 * Validates the upload (at most 2 MB, PNG/JPEG by magic bytes AND a full image
	 * decode, 64-4096 px each side), applies the JPEG EXIF orientation, centre-crops and
	 * re-encodes it as a 512x512 JPEG without any metadata, stores it and inserts the
	 * {@code media_file} row (own transaction, audited). Must be called with no
	 * transaction open (the storage write is an outbound call). A rejected upload
	 * leaves nothing stored.
	 *
	 * @return the new media file id
	 */
	UUID storeProfileImage(MultipartFile file);

	/** A short-lived (5 minute) signed URL of a live student photo of this tenant. */
	Optional<SignedImage> signUrl(UUID mediaFileId);

	/**
	 * Soft-deletes a student photo file that no student uses any more (the caller has
	 * checked that). Only {@code STUDENT_PROFILE} files are affected. Must run in the
	 * caller's transaction; the stored object is removed after commit.
	 */
	void discardProfileImage(UUID mediaFileId);

	record SignedImage(String url, Instant expiresAt) {
	}

}
