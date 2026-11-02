package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TenantLoggingInterceptorTest {

	private static final List<String> SHARED_PARTITIONS = List.of(
		"sapphire=elimu_demo", "ehr=elimu_demo", "e2e-sapphire=elimu_e2e", "e2e-ehr=elimu_e2e");

	private AppProperties myAppProperties;
	private RequestDetails myRequestDetails;
	private Map<Object, Object> myUserData;
	private Logger myLogger;
	private ListAppender<ILoggingEvent> myAppender;

	@BeforeEach
	void setUp() {
		myAppProperties = new AppProperties();
		myRequestDetails = mock(RequestDetails.class);
		myUserData = new HashMap<>();
		when(myRequestDetails.getUserData()).thenReturn(myUserData);

		myLogger = (Logger) LoggerFactory.getLogger(TenantLoggingInterceptor.class);
		myAppender = new ListAppender<>();
		myAppender.start();
		myLogger.addAppender(myAppender);
	}

	@AfterEach
	void tearDown() {
		myLogger.detachAppender(myAppender);
		MDC.clear();
	}

	@Test
	void noTenantConfig_claimPresent_logsClaim() {
		assertEquals("elimu_demo", tenantFor("sapphire", "elimu_demo"));
		assertNoWarnings();
	}

	@Test
	void noTenantConfig_noClaim_logsNoTenant() {
		assertNull(tenantFor("sapphire", null));
	}

	@Test
	void sharedServer_noClaim_logsMappedTenant() {
		myAppProperties.getTenant_logging().setPartition_tenants(SHARED_PARTITIONS);

		assertEquals("elimu_demo", tenantFor("sapphire", null));
		assertEquals("elimu_demo", tenantFor("ehr", null));
		assertEquals("elimu_e2e", tenantFor("e2e-sapphire", null));
		assertEquals("elimu_e2e", tenantFor("e2e-ehr", null));
		assertNoWarnings();
	}

	@Test
	void sharedServer_matchingClaim_logsTenantWithoutWarning() {
		myAppProperties.getTenant_logging().setPartition_tenants(SHARED_PARTITIONS);

		assertEquals("elimu_e2e", tenantFor("e2e-sapphire", "elimu_e2e"));
		assertNoWarnings();
	}

	@Test
	void sharedServer_mismatchedClaim_logsPartitionTenantAndWarns() {
		myAppProperties.getTenant_logging().setPartition_tenants(SHARED_PARTITIONS);

		assertEquals("elimu_e2e", tenantFor("e2e-sapphire", "elimu_demo"));

		assertEquals(1, myAppender.list.size());
		ILoggingEvent event = myAppender.list.get(0);
		assertEquals(Level.WARN, event.getLevel());
		assertTrue(event.getFormattedMessage().contains("elimu_demo"));
		assertTrue(event.getFormattedMessage().contains("elimu_e2e"));
	}

	@Test
	void sharedServer_unmappedPartition_logsNoTenant() {
		myAppProperties.getTenant_logging().setPartition_tenants(SHARED_PARTITIONS);

		assertNull(tenantFor("other", "elimu_demo"));
		assertNoWarnings();
	}

	@Test
	void sharedServer_defaultPartition_logsNoTenant() {
		myAppProperties.getTenant_logging().setPartition_tenants(SHARED_PARTITIONS);

		assertNull(tenantFor("DEFAULT", null));
		assertNull(tenantFor("DEFAULT", "elimu_demo"));
		assertNoWarnings();
	}

	@Test
	void dedicatedServer_noClaim_logsServerTenant() {
		myAppProperties.getTenant_logging().setTenant("baystate");

		assertEquals("baystate", tenantFor("sapphire", null));
		assertNoWarnings();
	}

	@Test
	void dedicatedServer_mismatchedClaim_logsServerTenantAndWarns() {
		myAppProperties.getTenant_logging().setTenant("baystate");

		assertEquals("baystate", tenantFor("sapphire", "elimu_demo"));
		assertEquals(1, myAppender.list.size());
		assertEquals(Level.WARN, myAppender.list.get(0).getLevel());
	}

	@Test
	void dedicatedServer_defaultPartition_logsNoTenant() {
		myAppProperties.getTenant_logging().setTenant("baystate");

		assertNull(tenantFor("DEFAULT", "baystate"));
		assertNoWarnings();
	}

	@Test
	void processingCompleted_clearsTenant() {
		myAppProperties.getTenant_logging().setTenant("baystate");
		TenantLoggingInterceptor interceptor = new TenantLoggingInterceptor(myAppProperties);
		when(myRequestDetails.getTenantId()).thenReturn("sapphire");

		interceptor.incomingRequestPreHandled(myRequestDetails);
		assertEquals("baystate", MDC.get(TenantLoggingInterceptor.TENANT));

		interceptor.processingCompleted(myRequestDetails);
		assertNull(MDC.get(TenantLoggingInterceptor.TENANT));
	}

	private String tenantFor(String partition, String claim) {
		MDC.remove(TenantLoggingInterceptor.TENANT);
		myUserData.clear();
		if (claim != null) {
			myUserData.put(TenantLoggingInterceptor.TENANT_CLAIM_USER_DATA_KEY, claim);
		}
		when(myRequestDetails.getTenantId()).thenReturn(partition);

		new TenantLoggingInterceptor(myAppProperties).incomingRequestPreHandled(myRequestDetails);
		return MDC.get(TenantLoggingInterceptor.TENANT);
	}

	private void assertNoWarnings() {
		assertTrue(myAppender.list.isEmpty(), () -> "Unexpected log events: " + myAppender.list);
	}
}
