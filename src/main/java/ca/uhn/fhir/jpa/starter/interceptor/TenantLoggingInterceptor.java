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

	// Order 1 runs this after AuthorizationInterceptor's hook on the same pointcut (default order 0),
	// which verifies the token and stores its tenant claim.
	@Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED, order = 1)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {
		String tenant = resolveTenant(requestDetails);
		if (tenant != null) {
			MDC.put(TENANT, tenant);
		}
	}

	@Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
	public void processingCompleted(RequestDetails requestDetails) {
		MDC.remove(TENANT);
	}

	// On a configured server the tenant comes from the server or the partition, since that reflects the
	// data actually touched; the claim only raises a warning when it disagrees. DEFAULT holds no tenant data.
	String resolveTenant(RequestDetails requestDetails) {
		Object claimValue = requestDetails.getUserData().get(TENANT_CLAIM_USER_DATA_KEY);
		String claim = claimValue instanceof String ? (String) claimValue : null;
		if (serverTenant == null && partitionTenants.isEmpty()) {
			return claim;
		}

		String partition = requestDetails.getTenantId();
		if (JpaConstants.DEFAULT_PARTITION_NAME.equals(partition)) {
			return null;
		}
		String fallback = serverTenant != null ? serverTenant : partitionTenants.get(partition);
		if (fallback != null && claim != null && !claim.equals(fallback)) {
			logger.warn("Token tenant claim {} does not match tenant {} for partition {}; logging {}",
				claim, fallback, partition, fallback);
		}
		return fallback;
	}
}
