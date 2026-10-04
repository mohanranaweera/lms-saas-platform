package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import java.util.Collection;
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

	public MediaConnectionLookupService(MediaProviderConnectionRepository repository,
			TenantMediaSettingsService settingsService) {
		this.repository = repository;
		this.settingsService = settingsService;
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
	public Optional<TenantChannelSummary> findTenantVideoChannel(MediaProvider provider) {
		return repository.findCurrentForTenant(provider)
			.map(connection -> new TenantChannelSummary(connection.getId(), connection.getStatus(),
					connection.isPlatformDisabled()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> currentConnectionForSameChannel(UUID connectionId) {
		Optional<MediaProviderConnection> original = repository.findById(connectionId);
		if (original.isEmpty() || original.get().getScope() != ConnectionScope.TENANT) {
			return Optional.empty();
		}
		return repository.findCurrentForTenant(original.get().getProvider())
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
