package com.lms.integrationmanagement.gateway.media;

/**
 * Who the connected provider account is.
 *
 * @param externalId the provider's own account/user id (Zoom user id, YouTube
 * channel id, Vimeo user URI; {@code null} for DynTube/Bunny.net accounts)
 * @param label display name for the settings page (email, channel title...)
 * @param accountType Zoom {@code BASIC}/{@code LICENSED}; {@code null} elsewhere
 */
public record ProviderIdentity(String externalId, String label, String accountType) {

}
