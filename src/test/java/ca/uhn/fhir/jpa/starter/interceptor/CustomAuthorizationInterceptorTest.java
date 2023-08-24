package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.AppProperties.Apikey;
import ca.uhn.fhir.jpa.starter.AppProperties.Oauth;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.test.utilities.JettyUtil;
import ca.uhn.fhir.util.TestUtil;

class CustomAuthorizationInterceptorTest {

	@Spy
	AppProperties mockConfig;

	@Spy
	Oauth mockOAuth;

	@Spy
	Apikey mockApikey;

	@Mock
	RequestDetails mockRequestDetails;

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

	private static final String TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0.NHVaYe26MbtOYhSKkoKYdFVomg4i8ZJd8_-RU8VNbftc4TSMb4bXP3l3YlNWACwyXPGffz5aXHc6lty1Y2t4SWRqGteragsVdZufDn5BlnJl9pdR_kdVFUsra2rWKEofkZeIC4yWytE58sMIihvo9H1ScmmVwBcQP6XETqYd0aSHp1gOa9RdUPDvoXQ5oqygTqVtxaDr6wUFKrKItgBMzWIdNZ6y7O9E0DhEPTbE9rfBo6KTFsHAZnMg4k68CDp2woYIaXbmYTWcvbzIuHO7_37GT79XdIwkm95QJ7hYC9RiwrV7mesbY4PAahERJawntho0my942XheVLmGwLMBkQ";
	private static final String PATIENT_ID = "12345";
	private static final String OBSERVATION_ID = "1234";
	private static final String JWK_URL = "test-jwk-url";
	private static final String ROLE_FHIR_ADMIN = "fhir4-admin";
	private static final String ROLE_FHIR_USER = "fhir4-user";
	private static final String API_KEY = "test-api-key";
	private static final String API_KEY_INVALID = "test-api-key-invalid";
	private static final String ROLE_INVALID_USER = "invalid-user";

	@BeforeEach
	public void before() throws IOException {
		MockitoAnnotations.openMocks(this);
		ourReturn = null;
		ourHitMethod = false;
		mockOAuth.setJwks_url(JWK_URL);
		mockOAuth.setAdmin_role(ROLE_FHIR_ADMIN);
		mockOAuth.setUser_role(ROLE_FHIR_USER);
		mockApikey.setKey(API_KEY);
		mockConfig.setOauth(mockOAuth);
		mockConfig.setApikey(mockApikey);
		ourInterceptor = new CustomAuthorizationInterceptor(mockConfig);
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
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000,TimeUnit.MILLISECONDS);
		HttpClientBuilder builder = HttpClientBuilder.create();
		builder.setConnectionManager(connectionManager);
		ourClient = builder.build();
	}

	@Test
	void testAllowAll() throws Exception {
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
		ourReturn = Collections.singletonList(createPatient(Integer.valueOf(PATIENT_ID)));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/" + PATIENT_ID);
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(Integer.valueOf(OBSERVATION_ID), "Patient/" + PATIENT_ID));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/" + OBSERVATION_ID);
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);
	}

	@Test
	void testdenyAll() throws Exception {
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
		ourReturn = Collections.singletonList(createPatient(Integer.valueOf(PATIENT_ID)));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/" + PATIENT_ID);
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(403, status.getStatusLine().getStatusCode());
		assertFalse(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(Integer.valueOf(OBSERVATION_ID), "Patient/" + PATIENT_ID));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/" + OBSERVATION_ID);
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
		ourServlet.registerInterceptor(ourInterceptor);

		HttpGet httpGet;
		HttpResponse status;
		String response;

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createPatient(Integer.valueOf(PATIENT_ID)));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/" + PATIENT_ID);
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);

		ourHitMethod = false;
		ourReturn = Collections.singletonList(createObservation(Integer.valueOf(OBSERVATION_ID), "Patient/" + PATIENT_ID));
		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/" + OBSERVATION_ID);
		status = ourClient.execute(httpGet);
		response = extractResponseAndClose(status);
		logger.info(response);
		assertEquals(200, status.getStatusLine().getStatusCode());
		assertTrue(ourHitMethod);
	}

	@Test
	void testAuthorizedInPatientCompartmentRule() throws Exception {
		List<IAuthRule> expectedPatientCompartmentRuleList = getPatientCompartmentRuleList(PATIENT_ID);
		when(mockRequestDetails.getResourceName()).thenReturn("Observation");
		List<IAuthRule> actualPatientCompartmentRuleList = ourInterceptor.authorizedInPatientCompartmentRule(mockRequestDetails, PATIENT_ID);

		assertEquals(expectedPatientCompartmentRuleList.toString(), actualPatientCompartmentRuleList.toString());
	}

	@Test
	void testForValidApikey() throws Exception {
		List<IAuthRule> expectedAllowAllRule = new RuleBuilder().allowAll().build();
		when(mockRequestDetails.getHeader("x-api-key")).thenReturn(API_KEY);
		when(mockRequestDetails.getResourceName()).thenReturn("Observation");
		List<IAuthRule> rule = ourInterceptor.authorizeApiKey(mockRequestDetails);
		IAuthRule actualAllowAllRule = rule.get(0);

		assertEquals(expectedAllowAllRule.get(0).toString(), actualAllowAllRule.toString());
	}

	@Test
	void testForInvalidApikey() throws Exception {
		when(mockRequestDetails.getHeader("x-api-key")).thenReturn(API_KEY_INVALID);
		when(mockRequestDetails.getResourceName()).thenReturn("Observation");

		assertThrows(AuthenticationException.class, () -> ourInterceptor.authorizeApiKey(mockRequestDetails));
	}

	@Test
	void testAuthorizeOAuth() throws Exception {
		List<IAuthRule> expectedAllowAllRule = new RuleBuilder().allowAll().build();
		ArrayList<String> clientRoles = new ArrayList<>();
		clientRoles.add(ROLE_FHIR_ADMIN);
		clientRoles.add(ROLE_FHIR_USER);
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(any())).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenAnswer((Answer<Void>) invocation -> null);
			mockedStatic.when(() -> OAuth2Helper.getClientRoles(any(), any())).thenReturn(clientRoles);
			List<IAuthRule> actualAllowAllRule = ourInterceptor.authorizeOAuth(mockRequestDetails);

			assertEquals(expectedAllowAllRule.toString(), actualAllowAllRule.toString());
		}
	}

	@Test
	void testAuthorizeOAuthDeleteWhenAdminRole() throws Exception {
		List<IAuthRule> expectedAllowAllRule = new RuleBuilder().allowAll().build();
		ArrayList<String> adminClientRoles = new ArrayList<>();
		adminClientRoles.add(ROLE_FHIR_ADMIN);
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(mockRequestDetails)).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenAnswer((Answer<Void>) invocation -> null);
			mockedStatic.when(() -> OAuth2Helper.getClientRoles(any(), any())).thenReturn(adminClientRoles);
			List<IAuthRule> actualAllowAllRule = ourInterceptor.authorizeOAuth(mockRequestDetails);

			assertEquals(expectedAllowAllRule.toString(), actualAllowAllRule.toString());
		}
	}

	@Test
	void testAuthorizeOAuthDeleteWhenUserRole() throws Exception {
		List<IAuthRule> expectedDenyAllRule = new RuleBuilder().allow().metadata().andThen().denyAll().build();
		ArrayList<String> userClientRoles = new ArrayList<>();
		userClientRoles.add(ROLE_FHIR_USER);
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(mockRequestDetails)).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenAnswer((Answer<Void>) invocation -> null);
			mockedStatic.when(() -> OAuth2Helper.getClientRoles(any(), any())).thenReturn(userClientRoles);
			List<IAuthRule> actualDenyAllRule = ourInterceptor.authorizeOAuth(mockRequestDetails);
			assertEquals(expectedDenyAllRule.toString(), actualDenyAllRule.toString());
		}
	}

	@Test
	void testAuthorizeOAuthWithExpiredToken() throws Exception {
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(mockRequestDetails)).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenThrow(TokenExpiredException.class);

			assertThrows(AuthenticationException.class, () -> ourInterceptor.authorizeOAuth(mockRequestDetails));
		}
	}

	@Test
	void testAuthorizeOAuthWithTokenHavingInvalidRole() throws Exception {
		List<IAuthRule> expectedDenyAllRule = new RuleBuilder().allow().metadata().andThen().denyAll().build();
		ArrayList<String> invalidClientRoles = new ArrayList<>();
		invalidClientRoles.add(ROLE_INVALID_USER);
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(mockRequestDetails)).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenAnswer((Answer<Void>) invocation -> null);
			mockedStatic.when(() -> OAuth2Helper.getClientRoles(any(), any())).thenReturn(invalidClientRoles);
			List<IAuthRule> actualDenyAllRule = ourInterceptor.authorizeOAuth(mockRequestDetails);

			assertEquals(expectedDenyAllRule.toString(), actualDenyAllRule.toString());
		}
	}

	@Test
	void testAuthorizeOAuthWithTokenHavingPatientClaim() throws Exception {
		List<IAuthRule> expectedPatientCompartmentRule = getPatientCompartmentRuleList(PATIENT_ID);
		ArrayList<String> clientRoles = new ArrayList<>();
		clientRoles.add(ROLE_FHIR_ADMIN);
		clientRoles.add(ROLE_FHIR_USER);
		try (MockedStatic<OAuth2Helper> mockedStatic = mockStatic(OAuth2Helper.class)) {
			mockedStatic.when(() -> OAuth2Helper.getToken(mockRequestDetails)).thenReturn(TOKEN);
			when(mockRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
			when(mockRequestDetails.getResourceName()).thenReturn("Observation");
			mockedStatic.when(() -> OAuth2Helper.verify(any(), any())).thenAnswer((Answer<Void>) invocation -> null);
			mockedStatic.when(() -> OAuth2Helper.getClientRoles(any(), any())).thenReturn(clientRoles);
			mockedStatic.when(() -> OAuth2Helper.getClaimAsString(any(DecodedJWT.class), anyString()))
					.thenReturn(PATIENT_ID);
			mockedStatic.when(() -> OAuth2Helper.canBeInPatientCompartment(anyString())).thenReturn(true);
			List<IAuthRule> actualPatientCompartmentRule = ourInterceptor.authorizeOAuth(mockRequestDetails);

			assertEquals(expectedPatientCompartmentRule.toString(), actualPatientCompartmentRule.toString());
		}
	}

	private List<IAuthRule> getPatientCompartmentRuleList(String patientId) {
		IdType patientIdType = new IdType("Patient", patientId);
		return new RuleBuilder().allow().read().allResources().inCompartment("Patient", patientIdType).andThen().allow()
				.patch().allRequests().andThen().allow().write().allResources().inCompartment("Patient", patientIdType)
				.andThen().allow().delete().allResources().inCompartment("Patient", patientIdType).andThen().allow()
				.transaction().withAnyOperation().andApplyNormalRules().andThen().denyAll().build();
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

	@AfterAll
	public static void afterClassClearContext() throws Exception {
		JettyUtil.closeServer(ourServer);
		TestUtil.randomizeLocaleAndTimezone();
	}
}
