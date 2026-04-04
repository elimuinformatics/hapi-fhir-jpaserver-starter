package ca.uhn.fhir.jpa.starter.interceptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IRestfulServerDefaults;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class OAuthSearchNarrowingInterceptorTest {

	private OAuthSearchNarrowingInterceptor myInterceptor;
	private RequestDetails myRequestDetails;
	private HttpServletRequest myHttpServletRequest;
	private HttpServletResponse myHttpServletResponse;
	private AppProperties myAppProperties;

	@BeforeEach
	void setUp() {
		myAppProperties = new AppProperties();
		myAppProperties.getOauth().setEnabled(true);
		myInterceptor = new OAuthSearchNarrowingInterceptor(myAppProperties);

		myRequestDetails = mock(RequestDetails.class);
		myHttpServletRequest = mock(HttpServletRequest.class);
		myHttpServletResponse = mock(HttpServletResponse.class);

		IRestfulServerDefaults serverDefaults = mock(IRestfulServerDefaults.class);
		when(serverDefaults.getFhirContext()).thenReturn(FhirContext.forR4Cached());

		when(myRequestDetails.getServer()).thenReturn(serverDefaults);
		when(myRequestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.SEARCH_TYPE);
		when(myRequestDetails.getResourceName()).thenReturn("Observation");
		when(myRequestDetails.getParameters()).thenReturn(new HashMap<>());
	}

	@Test
	void hookIncomingRequestPostProcessed_oauthDisabled_doesNotNarrow() {
		myAppProperties.getOauth().setEnabled(false);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn("123");

			myInterceptor.hookIncomingRequestPostProcessed(myRequestDetails, myHttpServletRequest, myHttpServletResponse);
			verify(myRequestDetails, never()).setParameters(any());
		}
	}

	@Test
	void hookIncomingRequestPostProcessed_noBearerToken_doesNotNarrow() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(false);

			myInterceptor.hookIncomingRequestPostProcessed(myRequestDetails, myHttpServletRequest, myHttpServletResponse);
			verify(myRequestDetails, never()).setParameters(any());
		}
	}

	@Test
	void hookIncomingRequestPostProcessed_noPatientClaim_doesNotNarrow() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn(null);

			myInterceptor.hookIncomingRequestPostProcessed(myRequestDetails, myHttpServletRequest, myHttpServletResponse);
			verify(myRequestDetails, never()).setParameters(any());
		}
	}

	@Test
	void hookIncomingRequestPostProcessed_withPatientClaim_narrowsSearch() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myRequestDetails, "patient")).thenReturn("123");

			myInterceptor.hookIncomingRequestPostProcessed(myRequestDetails, myHttpServletRequest, myHttpServletResponse);
			verify(myRequestDetails).setParameters(any());
		}
	}
}
