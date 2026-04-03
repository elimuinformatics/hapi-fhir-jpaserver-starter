package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;

class OAuthSearchNarrowingInterceptorTest {

	private static final String EXPECTED_COMPARTMENT = "Patient/123";

	private TestableOAuthSearchNarrowingInterceptor myInterceptor;
	private RequestDetails myRequestDetails;
	private AppProperties myAppProperties;

	@BeforeEach
	void setUp() {
		myAppProperties = new AppProperties();
		myAppProperties.getOauth().setEnabled(true);
		myInterceptor = new TestableOAuthSearchNarrowingInterceptor(myAppProperties);
		myRequestDetails = mock(RequestDetails.class);
	}

	@Test
	void buildAuthorizedList_oauthDisabled_returnsEmptyList() {
		myAppProperties.getOauth().setEnabled(false);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn("123");

			AuthorizedList list = myInterceptor.callBuildAuthorizedList(myRequestDetails);
			assertFalse(containsCompartment(list, EXPECTED_COMPARTMENT));
		}
	}

	@Test
	void buildAuthorizedList_noBearerToken_returnsEmptyList() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(false);

			AuthorizedList list = myInterceptor.callBuildAuthorizedList(myRequestDetails);
			assertFalse(containsCompartment(list, EXPECTED_COMPARTMENT));
		}
	}

	@Test
	void buildAuthorizedList_noPatientClaim_returnsEmptyList() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn(null);

			AuthorizedList list = myInterceptor.callBuildAuthorizedList(myRequestDetails);
			assertFalse(containsCompartment(list, EXPECTED_COMPARTMENT));
		}
	}

	@Test
	void buildAuthorizedList_withPatientClaim_addsPatientCompartment() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn("123");

			AuthorizedList list = myInterceptor.callBuildAuthorizedList(myRequestDetails);
			assertTrue(containsCompartment(list, EXPECTED_COMPARTMENT));
		}
	}

	private boolean containsCompartment(AuthorizedList theList, String theExpectedCompartment) {
		try {
			for (Field nextField : AuthorizedList.class.getDeclaredFields()) {
				nextField.setAccessible(true);
				Object fieldValue = nextField.get(theList);
				if (fieldValue instanceof Collection<?> collection && collection.contains(theExpectedCompartment)) {
					return true;
				}
			}
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Unable to inspect AuthorizedList internals", e);
		}
		return false;
	}

	private static class TestableOAuthSearchNarrowingInterceptor extends OAuthSearchNarrowingInterceptor {
		TestableOAuthSearchNarrowingInterceptor(AppProperties config) {
			super(config);
		}

		AuthorizedList callBuildAuthorizedList(RequestDetails theRequestDetails) {
			return super.buildAuthorizedList(theRequestDetails);
		}
	}
}
