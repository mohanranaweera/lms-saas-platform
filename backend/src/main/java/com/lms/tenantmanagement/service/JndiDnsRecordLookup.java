package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.config.CustomDomainProperties;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

/**
 * {@link DnsRecordLookup} through the JDK's JNDI DNS provider (module
 * {@code jdk.naming.dns}), using the host's resolvers, with a short timeout
 * and one retry. The name is queried fully qualified (trailing dot) so no
 * search domain is appended.
 */
@Component
public class JndiDnsRecordLookup implements DnsRecordLookup {

	private final CustomDomainProperties properties;

	public JndiDnsRecordLookup(CustomDomainProperties properties) {
		this.properties = properties;
	}

	@Override
	public List<String> lookup(String name, String recordType) {
		Hashtable<String, String> env = new Hashtable<>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
		env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(properties.getDnsTimeoutMillis()));
		env.put("com.sun.jndi.dns.timeout.retries", "1");
		DirContext context = null;
		try {
			context = new InitialDirContext(env);
			Attributes attributes = context.getAttributes(name + ".", new String[] { recordType });
			Attribute attribute = attributes.get(recordType);
			List<String> values = new ArrayList<>();
			if (attribute != null) {
				NamingEnumeration<?> all = attribute.getAll();
				while (all.hasMore()) {
					values.add(String.valueOf(all.next()));
				}
			}
			return values;
		}
		catch (NameNotFoundException ex) {
			return List.of();
		}
		catch (NamingException ex) {
			throw new DnsLookupException(recordType + " lookup failed", ex);
		}
		finally {
			if (context != null) {
				try {
					context.close();
				}
				catch (NamingException ignored) {
					// nothing to release
				}
			}
		}
	}

}
