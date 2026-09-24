package com.lms.contentmanagement.material.service;

import com.lms.contentmanagement.material.domain.MaterialType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service-layer command for creating a {@code material} row (Wave 5, plan
 * §3/§4), mirroring {@code liveclassmanagement.service.NewSessionCommand}'s
 * exact shape/purpose - a single, explicit parameter object rather than a
 * 10+-argument {@code MaterialService#createMaterial} signature. Built by
 * {@code MaterialController} from the raw multipart request; carries no
 * business-rule decisions of its own (e.g. no default-materialType
 * resolution) - {@code MaterialService#createMaterial} is the single place
 * that interprets these fields.
 *
 * <p>{@code file} is {@code null} for every material type except an uploaded
 * file ({@code PDF}/{@code IMAGE}/{@code DOCUMENT}/{@code OTHER}); {@code
 * externalUrl} is populated only for {@code LINK}; {@code noteContent} only
 * for {@code NOTE}; {@code videoAssetId} only for {@code VIDEO}/{@code
 * RECORDING} - see {@code MaterialType}'s own javadoc for the full
 * per-type contract {@code MaterialService} enforces.
 */
public record MaterialCreateCommand(UUID courseId, UUID moduleId, UUID lessonId, String title,
		MaterialType materialType, MultipartFile file, String externalUrl, String noteContent, UUID videoAssetId,
		UUID sessionId, Integer maxDownloads, Instant availableFromAt, Instant expiryAt) {

}
