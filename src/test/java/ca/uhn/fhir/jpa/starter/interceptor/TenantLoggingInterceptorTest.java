package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import ca.uhn.fhir.rest.api.server.RequestDetails;

class TenantLoggingInterceptorTest {

	private final TenantLoggingInterceptor myInterceptor = new TenantLoggingInterceptor();
	private RequestDetails myRequestDetails;
	private Map<Object, Object> myUserData;

	@BeforeEach
	void setUp() {
		myRequestDetails = mock(RequestDetails.class);
		myUserData = new HashMap<>();
		when(myRequestDetails.getUserData()).thenReturn(myUserData);
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@Test
	void claimPresent_logsClaim() {
		myUserData.put(TenantLoggingInterceptor.TENANT_CLAIM_USER_DATA_KEY, "elimu_e2e");

		myInterceptor.incomingRequestPreHandled(myRequestDetails);

		assertEquals("elimu_e2e", MDC.get(TenantLoggingInterceptor.TENANT));
	}

	@Test
	void noClaim_logsNoTenant() {
		myInterceptor.incomingRequestPreHandled(myRequestDetails);

		assertNull(MDC.get(TenantLoggingInterceptor.TENANT));
	}

	@Test
	void subRequest_setsNoTenant() {
		when(myRequestDetails.isSubRequest()).thenReturn(true);
		myUserData.put(TenantLoggingInterceptor.TENANT_CLAIM_USER_DATA_KEY, "elimu_e2e");

		myInterceptor.incomingRequestPreHandled(myRequestDetails);

		assertNull(MDC.get(TenantLoggingInterceptor.TENANT));
	}

	@Test
	void processingCompleted_clearsTenant() {
		myUserData.put(TenantLoggingInterceptor.TENANT_CLAIM_USER_DATA_KEY, "elimu_e2e");
		myInterceptor.incomingRequestPreHandled(myRequestDetails);
		assertEquals("elimu_e2e", MDC.get(TenantLoggingInterceptor.TENANT));

		myInterceptor.processingCompleted(myRequestDetails);

		assertNull(MDC.get(TenantLoggingInterceptor.TENANT));
	}

	@Test
	void subRequestCompleted_keepsParentTenant() {
		MDC.put(TenantLoggingInterceptor.TENANT, "elimu_e2e");
		when(myRequestDetails.isSubRequest()).thenReturn(true);

		myInterceptor.processingCompleted(myRequestDetails);

		assertEquals("elimu_e2e", MDC.get(TenantLoggingInterceptor.TENANT));
	}
}
