package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.domain.TenantMediaIntegration;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationTeacher;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationRepository;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationTeacherRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link MediaConnectionApi} - tenant-scoped reads only. */
@Service
public class MediaConnectionLookupService implements MediaConnectionApi {

	private final MediaProviderConnectionRepository repository;

	private final TenantMediaSettingsService settingsService;

	private final TenantMediaIntegrationRepository integrations;

	private final TenantMediaIntegrationTeacherRepository integrationTeachers;

	public MediaConnectionLookupService(MediaProviderConnectionRepository repository,
			TenantMediaSettingsService settingsService, TenantMediaIntegrationRepository integrations,
			TenantMediaIntegrationTeacherRepository integrationTeachers) {
		this.repository = repository;
		this.settingsService = settingsService;
		this.integrations = integrations;
		this.integrationTeachers = integrationTeachers;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TeacherConnectionSummary> findTeacherConnection(UUID teacherUserId, MediaProvider provider) {
		return repository.findCurrentForTeacher(teacherUserId, provider)
			.map(connection -> new TeacherConnectionSummary(connection.getId(), connection.getStatus(),
					connection.getExternalAccountType()));
	}

	@Override
	public boolean isTeacherProviderEnabled(MediaProvider provider) {
		return settingsService.isEnabled(provider);
	}

	@Override
	@Transactional(readOnly = true)
	public List<VideoIntegrationSummary> listVideoIntegrations() {
		return integrations
			.findForProvider(MediaProvider.YOUTUBE, List.of(TenantMediaIntegration.Status.ACTIVE,
					TenantMediaIntegration.Status.DELETED))
			.stream()
			.map(this::summary)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<VideoIntegrationSummary> findVideoIntegration(UUID integrationId) {
		return integrations.findById(integrationId)
			.filter(integration -> integration.getProvider() == MediaProvider.YOUTUBE)
			.map(this::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isTeacherAllowedOnIntegration(UUID integrationId, UUID teacherUserId) {
		List<TenantMediaIntegrationTeacher> allowed = integrationTeachers.findForIntegration(integrationId);
		return allowed.isEmpty() || allowed.stream().anyMatch(row -> row.getTeacherUserId().equals(teacherUserId));
	}

	private VideoIntegrationSummary summary(TenantMediaIntegration integration) {
		Optional<MediaProviderConnection> live = repository.findCurrentForIntegration(integration.getId());
		boolean platformDisabled = repository.findAllForIntegration(integration.getId())
			.stream()
			.findFirst()
			.map(MediaProviderConnection::isPlatformDisabled)
			.orElse(false);
		return new VideoIntegrationSummary(integration.getId(), integration.getName(), integration.isActive(),
				live.map(MediaProviderConnection::getId).orElse(null),
				live.map(MediaProviderConnection::getStatus).orElse(null), platformDisabled,
				live.map(c -> YouTubeOAuthClient.grantsEditScope(c.getGrantedScopes())).orElse(false));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> currentConnectionForSameChannel(UUID connectionId) {
		Optional<MediaProviderConnection> original = repository.findById(connectionId);
		if (original.isEmpty() || original.get().getScope() != ConnectionScope.TENANT) {
			return Optional.empty();
		}
		if (original.get().getIntegrationId() == null) {
			return Optional.empty();
		}
		return repository.findCurrentForIntegration(original.get().getIntegrationId())
			.filter(MediaProviderConnection::isActive)
			.filter(current -> Objects.equals(current.getExternalAccountId(), original.get().getExternalAccountId()))
			.map(MediaProviderConnection::getId);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, MediaConnectionStatus> connectionStatuses(Collection<UUID> connectionIds) {
		return repository.findByIds(connectionIds)
			.stream()
			.collect(Collectors.toMap(MediaProviderConnection::getId, MediaProviderConnection::getStatus));
	}

}
