package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.AppProperties.Apikey;
import ca.uhn.fhir.jpa.starter.AppProperties.Oauth;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.test.utilities.JettyUtil;

class CustomAuthorizationInterceptorTest {

	@Spy
	AppProperties mockConfig = new AppProperties();

	@Spy
	Oauth mockOAuth = new Oauth();

	@Spy
	Apikey mockApikey = new Apikey();

	@Mock
	OAuth2Helper mockOAuth2Helper;

	@InjectMocks
	CustomAuthorizationInterceptor ourInterceptor;

	private static Logger logger = LoggerFactory.getLogger(CustomAuthorizationInterceptorTest.class);
	private static RestfulServer ourServlet;
	private static boolean ourHitMethod;
	private static List<Resource> ourReturn;
	private static int ourPort;
	private static CloseableHttpClient ourClient;
	private static Server ourServer;
	private static final FhirContext ourCtx = FhirContext.forR4();

	@BeforeEach
	public void before() throws FileNotFoundException {
		MockitoAnnotations.openMocks(this);
		ourReturn = null;
		ourHitMethod = false;
	}

	@BeforeAll
	public static void beforeClass() throws Exception {
		ourServer = new Server(0);

		MockPatientResourceProvider patProvider = new MockPatientResourceProvider();
		MockObservationResourceProvider obsProv = new MockObservationResourceProvider();

		ServletHandler proxyHandler = new ServletHandler();
		ourServlet = new RestfulServer(ourCtx);
		ourServlet.setFhirContext(ourCtx);
		ourServlet.registerProviders(patProvider, obsProv);
		ourServlet.setDefaultResponseEncoding(EncodingEnum.JSON);
		ServletHolder servletHolder = new ServletHolder(ourServlet);
		proxyHandler.addServletWithMapping(servletHolder, "/fhir/*");
		ourServer.setHandler(proxyHandler);
		JettyUtil.startServer(ourServer);
		ourPort = JettyUtil.getPortForStartedServer(ourServer);

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000,
				TimeUnit.MILLISECONDS);
		HttpClientBuilder builder = HttpClientBuilder.create();
		builder.setConnectionManager(connectionManager);
		ourClient = builder.build();

	}

	private Resource createPatient(Integer theId) {
		Patient retVal = new Patient();
		if (theId != null) {
			retVal.setId(new IdType("Patient", (long) theId));
		}
		retVal.addName().setFamily("FAM");
		return retVal;
	}

	private Observation createObservation(Integer theId, String theSubjectId) {
		Observation retVal = new Observation();
		if (theId != null) {
			retVal.setId(new IdType("Observation", (long) theId));
		}
		retVal.getCode().setText("OBS");
		retVal.setSubject(new Reference(theSubjectId));

		if (theSubjectId != null && theSubjectId.startsWith("#")) {
			Patient p = new Patient();
			p.setId(theSubjectId);
			p.setActive(true);
			retVal.addContained(p);
		}

		return retVal;
	}

	private String extractResponseAndClose(HttpResponse status) throws IOException {
		if (status.getEntity() == null) {
			return null;
		}
		String responseContent;
		responseContent = IOUtils.toString(status.getEntity().getContent(), StandardCharsets.UTF_8);
		IOUtils.closeQuietly(status.getEntity().getContent());
		return responseContent;
	}

	@Test
	void testAllowAll() throws Exception {
		mockConfig.setOauth(mockOAuth);
		ourServlet.registerInterceptor(new CustomAuthorizationInterceptor(mockConfig) {
			@Override
			public List<IAuthRule> buildRuleList(RequestDetails theRequest) {
				return new RuleBuilder().allowAll().build();
			}
		});

		HttpGet httpGet;
		HttpResponse status;
		String response;

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createPatient(1));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/1");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(10, "Patient/1"));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/10");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);
	}

	@Test
	void testdenyAll() throws Exception {
		mockConfig.setOauth(mockOAuth);
		ourServlet.registerInterceptor(new CustomAuthorizationInterceptor(mockConfig) {
			@Override
			public List<IAuthRule> buildRuleList(RequestDetails theRequest) {
				return new RuleBuilder().allow().metadata().andThen().denyAll().build();
			}
		});

		HttpGet httpGet;
		HttpResponse status;
		String response;

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(10, "Patient/1"));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/10");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(403, status.getStatusLine().getStatusCode());
		assertFalse(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createPatient(1));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/1");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(403, status.getStatusLine().getStatusCode());
		assertFalse(ourHitMethod);

		ourHitMethod = false;
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/metadata");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
	}

	@Test
	void testOAuthEnabledFalse() throws Exception {
		mockConfig.setOauth(mockOAuth);
		when(mockConfig.getOauth()).thenReturn(mockOAuth);
		ourInterceptor = new CustomAuthorizationInterceptor(mockConfig);

		ourServlet.registerInterceptor(ourInterceptor);

		HttpGet httpGet;
		HttpResponse status;
		String response;

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createPatient(1));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/1");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(10, "Patient/1"));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/10");
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);
	}

	@Test
	void testAuthorizedInPatientCompartmentRule() throws Exception {
		List<IAuthRule> expectedReadRule = new RuleBuilder().allow().read().allResources()
				.inCompartment("Patient", new IdType("Patient", "1")).build();
		List<IAuthRule> expectedWriteRule = new RuleBuilder().allow().write().allResources()
				.inCompartment("Patient", new IdType("Patient", "1")).build();
		List<IAuthRule> expectedDeleteRule = new RuleBuilder().allow().delete().allResources()
				.inCompartment("Patient", new IdType("Patient", "1")).build();
		List<IAuthRule> expectedTransactionRule = new RuleBuilder().allow().transaction().withAnyOperation()
				.andApplyNormalRules().andThen().build();
		List<IAuthRule> expectedPatchRule = new RuleBuilder().allow().patch().allRequests().andThen().build();

		RequestDetails mockRequestDetails = Mockito.mock(RequestDetails.class);
		Mockito.when(mockRequestDetails.getResourceName()).thenReturn("Observation");
		List<IAuthRule> rule = ourInterceptor.authorizedInPatientCompartmentRule(mockRequestDetails, "1");

		IAuthRule actualReadRule = rule.get(0);
		IAuthRule actualPatchRule = rule.get(1);
		IAuthRule actualWriteRule = rule.get(2);
		IAuthRule actualDeleteRule = rule.get(3);
		IAuthRule actualtransactionRule = rule.get(4);

		assertEquals(expectedReadRule.get(0).toString(), actualReadRule.toString());
		assertEquals(expectedPatchRule.get(0).toString(), actualPatchRule.toString());
		assertEquals(expectedWriteRule.get(0).toString(), actualWriteRule.toString());
		assertEquals(expectedDeleteRule.get(0).toString(), actualDeleteRule.toString());
		assertEquals(expectedTransactionRule.get(0).toString(), actualtransactionRule.toString());
	}

	@Test
	void testForValidApikey() throws Exception {
		List<IAuthRule> expectedAllowAllRule = new RuleBuilder().allowAll().build();
		RequestDetails mockRequestDetails = Mockito.mock(RequestDetails.class);
		when(mockApikey.getKey()).thenReturn("test-api-key");
		when(mockConfig.getApikey()).thenReturn(mockApikey);
		when(mockRequestDetails.getHeader("x-api-key")).thenReturn("test-api-key");
		when(mockRequestDetails.getResourceName()).thenReturn("Observation");
		List<IAuthRule> rule = ourInterceptor.authorizeApiKey(mockRequestDetails);
		IAuthRule actualAllowAllRule = rule.get(0);

		assertEquals(expectedAllowAllRule.get(0).toString(), actualAllowAllRule.toString());
	}

	@Test
	void testForInvalidApikey() throws Exception {
		RequestDetails mockRequestDetails = Mockito.mock(RequestDetails.class);
		when(mockApikey.getKey()).thenReturn("test-api-key-mismatch");
		when(mockConfig.getApikey()).thenReturn(mockApikey);
		when(mockRequestDetails.getHeader("x-api-key")).thenReturn("test-api-key");
		when(mockRequestDetails.getResourceName()).thenReturn("Observation");

		assertThrows(AuthenticationException.class, () -> ourInterceptor.authorizeApiKey(mockRequestDetails));
	}

	public static class MockObservationResourceProvider implements IResourceProvider {

		@Override
		public Class<? extends IBaseResource> getResourceType() {
			return Observation.class;
		}

		@Read(version = true)
		public Observation read(@IdParam IdType theId) {
			ourHitMethod = true;
			if (ourReturn.isEmpty()) {
				throw new ResourceNotFoundException(theId);
			}
			return (Observation) ourReturn.get(0);
		}
	}

	public static class MockPatientResourceProvider implements IResourceProvider {

		@Override
		public Class<? extends IBaseResource> getResourceType() {
			return Patient.class;
		}

		@Read(version = true)
		public Patient read(@IdParam IdType theId) {
			ourHitMethod = true;
			if (ourReturn.isEmpty()) {
				throw new ResourceNotFoundException(theId);
			}
			return (Patient) ourReturn.get(0);
		}
	}

}
