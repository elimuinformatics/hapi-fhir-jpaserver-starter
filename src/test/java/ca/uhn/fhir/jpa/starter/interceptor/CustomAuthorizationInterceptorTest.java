package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.AppProperties.Oauth;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.test.utilities.JettyUtil;

class CustomAuthorizationInterceptorTest {

	@Spy
	AppProperties mockConfig = new AppProperties();

	@Spy
	Oauth ourOAuth = new Oauth();

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
//	private static final String TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjY2NTQwYzc3LTcwNGEtNDI1OC05OGNjLTM1MjY2MDU2MjY1YiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlRlc3QgVG9rZW4iLCJhZG1pbiI6dHJ1ZX0.JQJYLXI49QRm8NawZIiILoqotxugyiSOyYjlCsiDB0y0l-jM_pmyTJEvLjoeOix_5coARh60hiuQHyPTON11klECvlu6CbY0jHvEj68l2qsVJueWgEM__mjDLJfXOeQlZq6Fiyl18dgCbCJ-_g-YLAIAuvH2DRCErBnju88myHpcio_Atd5S5gXkg2ik2-zlxAevuem5ImmT5u9fq3n3T1N6VykH1ej5mrQMlqLG9oCvMR-yDEqQRd7driQzzZWbdNmpV4J_Fqws_Emye1RnQT2MWmH5Fa2S5sa9Wz2-uUGCwvJv3SJ3oui63CA0uDqwS6q7XWtSGwDk2Y52BOySFg";


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
		mockConfig.setOauth(ourOAuth);
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
		mockConfig.setOauth(ourOAuth);
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
		mockConfig.setOauth(ourOAuth);
		when(mockConfig.getOauth()).thenReturn(ourOAuth);
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

//	@Test
//	void testOAuthEnabledTrue() throws Exception {
//
//		DecodedJWT jwt = mock(DecodedJWT.class);
//	    Claim clientIdClaim = mock(Claim.class);
//	    when(jwt.getClaim("client_id")).thenReturn(clientIdClaim);
//	    when(jwt.getClaim("patient")).thenReturn(mock(Claim.class));
//	    when(jwt.getClaim("patient").asString()).thenReturn("123");
//		ourOAuth.setEnabled(true);
//		ourOAuth.setClient_id("fhir4-api");
//		ourOAuth.setUser_role("fhir4-user");
//
//		when(mockConfig.getOauth()).thenReturn(ourOAuth);
//		when(mockConfig.getOauth().getJwks_url()).thenReturn("https://example.com/jwks");
//		OAuth2Helper oauth2Helper = mock(OAuth2Helper.class);
//		when(oauth2Helper.getClientRoles(jwt, ourOAuth.getClient_id())).thenReturn(Collections.singletonList("admin"));
//		when(oauth2Helper.getClaimAsString(jwt, "patient")).thenReturn("123");
//
//		JWT jwtLibrary = mock(JWT.class);
//		when(jwtLibrary.decode("valid_token")).thenReturn(jwt);
//
//		try (MockedStatic<JWT> mockedJwt = mockStatic(JWT.class)) {
//			mockedJwt.when(() -> JWT.decode("valid_token")).thenReturn(jwt);
//		}
//
//		ourServlet.registerInterceptor(ourInterceptor);
//
//		HttpGet httpGet;
//		HttpResponse status;
//		String response;
//
//		ourHitMethod = false;
//		ourReturn = Collections.singletonList(createPatient(1));
//		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Patient/1");
//		httpGet.addHeader("Authorization", "Bearer " + TOKEN);
//		status = ourClient.execute(httpGet);
//		response = extractResponseAndClose(status);
//		logger.info(response);
//		assertEquals(200, status.getStatusLine().getStatusCode());
//		assertTrue(ourHitMethod);
//
//		ourHitMethod = false;
//		ourReturn = Collections.singletonList(createObservation(10, "Patient/1"));
//		httpGet = new HttpGet("http://localhost:" + ourPort + "/fhir/Observation/10");
//		httpGet.addHeader("Authorization", "Bearer " + TOKEN);
//		status = ourClient.execute(httpGet);
//		response = extractResponseAndClose(status);
//		logger.info(response);
//		assertEquals(200, status.getStatusLine().getStatusCode());
//		assertTrue(ourHitMethod);
//	}

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
