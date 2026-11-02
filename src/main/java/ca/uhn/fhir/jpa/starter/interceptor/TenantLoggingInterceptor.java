package ca.uhn.fhir.jpa.starter.interceptor;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;

// On a configured server the tenant comes from the server or the partition, since that reflects the
// data actually touched; the token's claim only raises a warning when it disagrees. DEFAULT holds no
// tenant data. A server with no tenant configuration logs the claim.
@Interceptor
public class TenantLoggingInterceptor {
	private static final Logger logger = LoggerFactory.getLogger(TenantLoggingInterceptor.class);

	public static final String TENANT_CLAIM = "tenant";
	public static final String TENANT_CLAIM_USER_DATA_KEY = TenantLoggingInterceptor.class.getName() + ".tenantClaim";
	static final String TENANT = "tenant";

	private final String serverTenant;
	private final Map<String, String> partitionTenants;

	public TenantLoggingInterceptor(AppProperties config) {
		this.serverTenant = StringUtils.trimToNull(config.getTenant_logging().getTenant());
		this.partitionTenants = config.getTenant_logging().parsePartitionTenants();
	}

	// The configured tenant doesn't depend on the token, so it is set once the partition is known and
	// before authorization runs; authorization's own log lines and rejections then carry it too.
	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	public boolean incomingRequestPostProcessed(RequestDetails requestDetails) {
		String tenant = configuredTenant(requestDetails.getTenantId());
		if (tenant != null) {
			MDC.put(TENANT, tenant);
		}
		return true;
	}

	// Order 1 runs this after AuthorizationInterceptor's hook on the same pointcut (default order 0),
	// which verifies the token and stores its tenant claim.
	@Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED, order = 1)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {
		Object claimValue = requestDetails.getUserData().get(TENANT_CLAIM_USER_DATA_KEY);
		String claim = claimValue instanceof String ? (String) claimValue : null;
		if (claim == null) {
			return;
		}
		if (!isConfigured()) {
			MDC.put(TENANT, claim);
			return;
		}

		String partition = requestDetails.getTenantId();
		String configured = configuredTenant(partition);
		if (configured != null && !claim.equals(configured)) {
			logger.warn("Token tenant claim {} does not match tenant {} for partition {}; logging {}",
				claim, configured, partition, configured);
		}
	}

	@Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
	public void processingCompleted(RequestDetails requestDetails) {
		MDC.remove(TENANT);
	}

	private boolean isConfigured() {
		return serverTenant != null || !partitionTenants.isEmpty();
	}

	private String configuredTenant(String partition) {
		if (!isConfigured() || JpaConstants.DEFAULT_PARTITION_NAME.equals(partition)) {
			return null;
		}
		return serverTenant != null ? serverTenant : partitionTenants.get(partition);
	}
}
