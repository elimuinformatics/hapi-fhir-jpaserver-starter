package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.AppProperties.Oauth;
import ca.uhn.fhir.model.api.IQueryParameterOr;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.param.BaseAndListParam;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;
import ca.uhn.fhir.test.utilities.server.RestfulServerExtension;

class CustomSearchNarrowingInterceptorTest {

	@Spy
	AppProperties mockConfig;

	@Spy
	Oauth mockOAuth;

	@InjectMocks
	CustomSearchNarrowingInterceptor ourSearchNarrowingInterceptor;

	private static Logger logger = LoggerFactory.getLogger(CustomSearchNarrowingInterceptorTest.class);
	private static List<Resource> ourReturn;
	private static String ourLastHitMethod;
	private static TokenAndListParam ourLastIdParam;
	private static ReferenceAndListParam ourLastPatientParam;
	private static final FhirContext ourCtx = FhirContext.forR4();
	private static final String PATIENT_ID = "12345";
	private static final String TOKEN_WITH_PATIENT_CLAIM = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJwYXRpZW50IjoiMTIzNDUifQ.U6r_6XMH5Bw6Lc4CBwcwrj5HvdAZeL8ZlIQXNnyJGow";
	private static final String TOKEN_WITHOUT_PATIENT_CLAIM = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
	public IGenericClient myClient;

	@RegisterExtension
	public RestfulServerExtension myRestfulServerExtension = new RestfulServerExtension(ourCtx)
			.registerProvider(new MockPatientResourceProvider())
			.registerProvider(new MockObservationResourceProvider());

	@BeforeEach
	public void before() {
		MockitoAnnotations.openMocks(this);
		ourReturn = Collections.emptyList();
		ourLastHitMethod = null;
		ourLastIdParam = null;
		ourLastPatientParam = null;
		mockOAuth.setEnabled(true);
		mockConfig.setOauth(mockOAuth);
		ourSearchNarrowingInterceptor = new CustomSearchNarrowingInterceptor(mockConfig) {
			@Override
			protected AuthorizedList buildAuthorizedList(RequestDetails theRequestDetails) {
				return super.buildAuthorizedList(theRequestDetails);
			}
		};
		myRestfulServerExtension.registerInterceptor(ourSearchNarrowingInterceptor);
		myClient = myRestfulServerExtension.getFhirClient();
	}

	@Test
	void testSearchNarrowObservationWithPatientClaim() throws Exception {
		myClient.registerInterceptor(new BearerTokenAuthInterceptor() {
			@Override
			public void interceptRequest(IHttpRequest theRequest) {
				theRequest.addHeader("Authorization", "Bearer " + TOKEN_WITH_PATIENT_CLAIM);
			}
		});
		myClient.search().forResource("Observation").execute();
		logger.info("Patient Reference Param : {}", ourLastPatientParam);

		assertEquals("Observation.search", ourLastHitMethod);
		assertEquals("Patient/" + PATIENT_ID, toStrings(ourLastPatientParam).get(0));
	}

	@Test
	void testSearchNarrowObservationWithoutPatientClaim() throws Exception {
		myClient.registerInterceptor(new BearerTokenAuthInterceptor() {
			@Override
			public void interceptRequest(IHttpRequest theRequest) {
				theRequest.addHeader("Authorization", "Bearer " + TOKEN_WITHOUT_PATIENT_CLAIM);
			}
		});
		myClient.search().forResource("Observation").execute();
		logger.info("Patient Reference Param : {}", ourLastPatientParam);

		assertEquals("Observation.search", ourLastHitMethod);
		assertNull(ourLastPatientParam);
	}

	@Test
	void testSearchNarrowPatientWithPatientClaim() throws Exception {
		myClient.registerInterceptor(new BearerTokenAuthInterceptor() {
			@Override
			public void interceptRequest(IHttpRequest theRequest) {
				theRequest.addHeader("Authorization", "Bearer " + TOKEN_WITH_PATIENT_CLAIM);
			}
		});
		myClient.search().forResource("Patient").execute();
		logger.info("Patient Id Param : {}", ourLastIdParam);

		assertEquals("Patient.search", ourLastHitMethod);
		assertEquals("Patient/" + PATIENT_ID, toStrings(ourLastIdParam).get(0));
	}

	@Test
	void testSearchNarrowPatientWithoutPatientClaim() throws Exception {
		myClient.registerInterceptor(new BearerTokenAuthInterceptor() {
			@Override
			public void interceptRequest(IHttpRequest theRequest) {
				theRequest.addHeader("Authorization", "Bearer " + TOKEN_WITHOUT_PATIENT_CLAIM);
			}
		});
		myClient.search().forResource("Patient").execute();
		logger.info("Patient Id Param : {}", ourLastIdParam);

		assertEquals("Patient.search", ourLastHitMethod);
		assertNull(ourLastIdParam);
	}

	private List<String> toStrings(BaseAndListParam<? extends IQueryParameterOr<?>> theParams) {
		List<? extends IQueryParameterOr<? extends IQueryParameterType>> valuesAsQueryTokens = theParams
				.getValuesAsQueryTokens();

		return valuesAsQueryTokens.stream().map(IQueryParameterOr::getValuesAsQueryTokens)
				.map(t -> t.stream().map(j -> j.getValueAsQueryToken(ourCtx)).collect(Collectors.joining(",")))
				.collect(Collectors.toList());
	}

	public static class MockObservationResourceProvider implements IResourceProvider {

		@Override
		public Class<? extends IBaseResource> getResourceType() {
			return Observation.class;
		}

		@Search()
		public List<Resource> search(@OptionalParam(name = "_id") TokenAndListParam theIdParam,
				@OptionalParam(name = Observation.SP_PATIENT) ReferenceAndListParam thePatientParam) {
			ourLastHitMethod = "Observation.search";
			ourLastIdParam = theIdParam;
			ourLastPatientParam = thePatientParam;
			return ourReturn;
		}
	}

	public static class MockPatientResourceProvider implements IResourceProvider {

		@Override
		public Class<? extends IBaseResource> getResourceType() {
			return Patient.class;
		}

		@Search()
		public List<Resource> search(@OptionalParam(name = "_id") TokenAndListParam theIdParam) {
			ourLastHitMethod = "Patient.search";
			ourLastIdParam = theIdParam;
			return ourReturn;
		}
	}
}
