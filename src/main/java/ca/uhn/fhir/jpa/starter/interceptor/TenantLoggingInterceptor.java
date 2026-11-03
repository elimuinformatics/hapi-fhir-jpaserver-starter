package ca.uhn.fhir.jpa.starter.interceptor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;

// Logs the tenant from the verified token's claim. A request without a claim logs no tenant, and the
// prod log query guard leaves such lines out. Registered through hapi.fhir.custom-interceptor-classes.
@Interceptor
public class TenantLoggingInterceptor {
	public static final String TENANT_CLAIM = "tenant";
	public static final String TENANT_CLAIM_USER_DATA_KEY = TenantLoggingInterceptor.class.getName() + ".tenantClaim";
	static final String TENANT = "tenant";

	// Order 1 runs this after AuthorizationInterceptor's hook on the same pointcut (default order 0),
	// which verifies the token and stores its tenant claim. Sub-requests are skipped: batch entries
	// run on HAPI's bundle thread pool, where nothing clears MDC, so the tenant would leak to later
	// requests on that thread. The parent request's thread already carries the tenant.
	@Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED, order = 1)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {
		if (requestDetails.isSubRequest()) {
			return;
		}
		Object claim = requestDetails.getUserData().get(TENANT_CLAIM_USER_DATA_KEY);
		if (claim instanceof String && StringUtils.isNotBlank((String) claim)) {
			MDC.put(TENANT, (String) claim);
		}
	}

	@Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
	public void processingCompleted(RequestDetails requestDetails) {
		if (requestDetails.isSubRequest()) {
			return;
		}
		MDC.remove(TENANT);
	}
}
