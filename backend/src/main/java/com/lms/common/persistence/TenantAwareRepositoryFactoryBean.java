package com.lms.common.persistence;

import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.io.Serializable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.jpa.repository.support.JpaRepositoryImplementation;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

/**
 * Wires {@link TenantAwareRepositoryImpl} as the base class for any
 * repository whose domain type implements {@link TenantOwned}, and falls
 * back to standard Spring Data JPA behavior for every other repository
 * (e.g. a future platform-global, non-tenant-owned entity). Registered
 * platform-wide by {@code JpaRepositoryConfig} via
 * {@code @EnableJpaRepositories(repositoryFactoryBeanClass = ...)} so no
 * individual domain module has to opt in per repository beyond extending
 * {@link TenantAwareRepository}.
 */
public class TenantAwareRepositoryFactoryBean<R extends Repository<T, I>, T, I extends Serializable>
		extends JpaRepositoryFactoryBean<R, T, I> {

	private TenantContext tenantContext;

	public TenantAwareRepositoryFactoryBean(Class<? extends R> repositoryInterface) {
		super(repositoryInterface);
	}

	@Autowired
	public void setTenantContext(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	protected RepositoryFactorySupport createRepositoryFactory(EntityManager entityManager) {
		return new TenantAwareRepositoryFactory(entityManager, tenantContext);
	}

	private static final class TenantAwareRepositoryFactory extends JpaRepositoryFactory {

		private final EntityManager entityManager;

		private final TenantContext tenantContext;

		TenantAwareRepositoryFactory(EntityManager entityManager, TenantContext tenantContext) {
			super(entityManager);
			this.entityManager = entityManager;
			this.tenantContext = tenantContext;
		}

		@Override
		@SuppressWarnings({ "unchecked", "rawtypes" })
		protected JpaRepositoryImplementation<?, ?> getTargetRepository(RepositoryInformation information,
				EntityManager entityManager) {
			if (TenantOwned.class.isAssignableFrom(information.getDomainType())) {
				JpaEntityInformation<?, ?> entityInformation = getEntityInformation(information.getDomainType());
				return new TenantAwareRepositoryImpl(entityInformation, entityManager, tenantContext);
			}
			return super.getTargetRepository(information, entityManager);
		}

		@Override
		protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
			if (TenantOwned.class.isAssignableFrom(metadata.getDomainType())) {
				return TenantAwareRepositoryImpl.class;
			}
			return super.getRepositoryBaseClass(metadata);
		}

	}

}
