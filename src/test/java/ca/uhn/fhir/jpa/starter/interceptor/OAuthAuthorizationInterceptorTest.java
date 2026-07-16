package ca.uhn.fhir.jpa.starter.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpHeaders;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

class OAuthAuthorizationInterceptorTest {

	private static final String TEST_TOKEN = "test-token";

	private OAuthAuthorizationInterceptor myInterceptor;
	private RequestDetails myRequestDetails;
	private AppProperties myAppProperties;
	private DecodedJWT myDecodedJwt;

	@BeforeEach
	void setUp() {
		myAppProperties = new AppProperties();
		myAppProperties.getOauth().setEnabled(true);
		myAppProperties.getOauth().setClient_id("client-a");
		myAppProperties.getOauth().setUser_role("user-role");
		myAppProperties.getOauth().setAdmin_role("admin-role");
		myAppProperties.getOauth().setAudit_role("audit-api-role");
		myAppProperties.getOauth().setJwks_url("http://example.org/jwks");

		myInterceptor = new OAuthAuthorizationInterceptor(myAppProperties);
		myRequestDetails = mock(RequestDetails.class);
		myDecodedJwt = mock(DecodedJWT.class);

		when(myRequestDetails.getRequestPath()).thenReturn("Patient/123");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
		when(myRequestDetails.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TEST_TOKEN);
	}

	@Test
	void buildRuleList_oauthDisabled_allowsAll() {
		myAppProperties.getOauth().setEnabled(false);

		List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);

		assertRuleListMatches(rules, allowAllRules());
	}

	@Test
	void buildRuleList_missingAuthorization_throwsAuthenticationException() {
		when(myRequestDetails.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(false);

			assertThrows(AuthenticationException.class, () -> myInterceptor.buildRuleList(myRequestDetails));
		}
	}

	@Test
	void buildRuleList_tokenMetadataPath_isUnauthorized() {
		when(myRequestDetails.getRequestPath()).thenReturn("fhir/" + Constants.URL_TOKEN_METADATA);

		List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);

		assertRuleListMatches(rules, unauthorizedRules());
	}

	@Test
	void buildRuleList_unexpectedRuntimeInBuildRuleList_throwsAuthenticationException() {
		when(myRequestDetails.getRequestPath()).thenReturn(null);

		assertThrows(AuthenticationException.class, () -> myInterceptor.buildRuleList(myRequestDetails));
	}

	@Test
	void buildRuleList_noClientRoles_isUnauthorized() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of());
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
			helperMock.verify(() -> OAuth2Helper.verify(myDecodedJwt, "http://example.org/jwks"), times(1));
			helperMock.verify(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a"), times(1));
		}
	}

	@Test
	void buildRuleList_deleteWithAdminRole_allowsAll() {
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myDecodedJwt, "patient")).thenReturn(null);
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
			helperMock.verify(() -> OAuth2Helper.getClaimAsString(myDecodedJwt, "patient"), times(1));
		}
	}

	@Test
	void buildRuleList_deleteWithoutAdminRole_isUnauthorized() {
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_unrelatedRole_isUnauthorized() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("reporting-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_adminRoleNoPatientClaim_allowsAll() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myDecodedJwt, "patient")).thenReturn(null);
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventPost_userRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.POST);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventPost_auditRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.POST);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}


	@Test
	void buildRuleList_auditEventPostSearch_userRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.POST);
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_search");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventPostSearch_adminRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.POST);
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_search");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventPostSearch_auditRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.POST);
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_search");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}


	@Test
	void buildRuleList_auditEventGet_userRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventGet_adminRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventGet_auditRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventGet_auditRoleWithoutConfiguredAuditRole_isUnauthorized() {
		myAppProperties.getOauth().setAudit_role(null);
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventHistoryGet_userRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_history");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventHistoryGet_adminRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_history");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_auditEventHistoryGet_auditRole_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestPath()).thenReturn("AuditEvent/_history");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
				 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
		}
	}

	@Test
	void buildRuleList_nonAuditEventResource_auditRoleOnly_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("Observation");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
		when(myRequestDetails.getRequestPath()).thenReturn("Observation");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
				 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}


	@Test
	void buildRuleList_auditEventDelete_adminRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventDelete_auditRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.DELETE);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("audit-api-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventPatch_userRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.PATCH);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventPatch_adminRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.PATCH);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventPut_userRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.PUT);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_auditEventPut_adminRole_isUnauthorized() {
		when(myRequestDetails.getResourceName()).thenReturn("AuditEvent");
		when(myRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.PUT);

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("admin-role"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, unauthorizedRules());
		}
	}

	@Test
	void buildRuleList_userRoleWithPatientClaim_buildsCompartmentRules() {
		when(myRequestDetails.getResourceName()).thenReturn("Observation");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myDecodedJwt, "patient")).thenReturn("456");
			helperMock.when(() -> OAuth2Helper.canBeInPatientCompartment("Observation")).thenReturn(true);
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, patientCompartmentRules("456"));
			helperMock.verify(() -> OAuth2Helper.canBeInPatientCompartment("Observation"), times(1));
		}
	}

	@Test
	void buildRuleList_userRoleWithPatientClaim_nonCompartmentResource_allowsAll() {
		when(myRequestDetails.getResourceName()).thenReturn("Binary");

		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString())).thenAnswer(invocation -> null);
			helperMock.when(() -> OAuth2Helper.getClientRoles(myDecodedJwt, "client-a")).thenReturn(List.of("user-role"));
			helperMock.when(() -> OAuth2Helper.getClaimAsString(myDecodedJwt, "patient")).thenReturn("456");
			helperMock.when(() -> OAuth2Helper.canBeInPatientCompartment("Binary")).thenReturn(false);
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			List<IAuthRule> rules = myInterceptor.buildRuleList(myRequestDetails);
			assertRuleListMatches(rules, allowAllRules());
			helperMock.verify(() -> OAuth2Helper.canBeInPatientCompartment("Binary"), times(1));
		}
	}

	@Test
	void buildRuleList_tokenVerificationCheckedFailure_throwsAuthenticationException() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString()))
				.thenThrow(new NoSuchAlgorithmException("unsupported"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			assertThrows(AuthenticationException.class, () -> myInterceptor.buildRuleList(myRequestDetails));
		}
	}

	@Test
	void buildRuleList_tokenVerificationFailure_throwsAuthenticationException() {
		try (MockedStatic<OAuth2Helper> helperMock = mockStatic(OAuth2Helper.class);
			 MockedStatic<JWT> jwtMock = mockStatic(JWT.class)) {
			helperMock.when(() -> OAuth2Helper.hasToken(myRequestDetails)).thenReturn(true);
			helperMock.when(() -> OAuth2Helper.getToken(myRequestDetails)).thenReturn(TEST_TOKEN);
			helperMock.when(() -> OAuth2Helper.verify(any(DecodedJWT.class), anyString()))
				.thenThrow(new RuntimeException("bad signature"));
			jwtMock.when(() -> JWT.decode(TEST_TOKEN)).thenReturn(myDecodedJwt);

			assertThrows(AuthenticationException.class, () -> myInterceptor.buildRuleList(myRequestDetails));
		}
	}

	private void assertRuleListMatches(List<IAuthRule> actual, List<IAuthRule> expected) {
		assertEquals(expected.size(), actual.size(), "Rule count mismatch");
		for (int i = 0; i < expected.size(); i++) {
			assertEquals(expected.get(i).toString(), actual.get(i).toString(), "Rule mismatch at index " + i);
		}
	}

	private List<IAuthRule> allowAllRules() {
		return new RuleBuilder()
			.allowAll()
			.build();
	}

	private List<IAuthRule> unauthorizedRules() {
		return new RuleBuilder()
			.allow().metadata().andThen()
			.denyAll()
			.build();
	}

	private List<IAuthRule> patientCompartmentRules(String patientId) {
		IdType patientIdType = new IdType("Patient", patientId);
		return new RuleBuilder()
			.allow().read().allResources().inCompartment("Patient", patientIdType).andThen()
			.allow().patch().allRequests().andThen()
			.allow().write().allResources().inCompartment("Patient", patientIdType).andThen()
			.allow().delete().allResources().inCompartment("Patient", patientIdType).andThen()
			.allow().transaction().withAnyOperation().andApplyNormalRules().andThen()
			.denyAll()
			.build();
	}
}
