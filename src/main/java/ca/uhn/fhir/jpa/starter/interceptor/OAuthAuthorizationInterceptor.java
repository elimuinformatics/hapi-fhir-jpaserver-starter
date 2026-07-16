package ca.uhn.fhir.jpa.starter.interceptor;

import java.security.GeneralSecurityException;
import java.util.List;

import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Strings;

import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.util.OAuth2Helper;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

@Interceptor
public class OAuthAuthorizationInterceptor extends AuthorizationInterceptor {
	private static final Logger logger = LoggerFactory.getLogger(OAuthAuthorizationInterceptor.class);
	private static final String PATIENT_RESOURCE = "Patient";
	private static final String AUDIT_EVENT_RESOURCE = "AuditEvent";
	private static final String SEARCH_PATH_SUFFIX = "/_search";

	private final AppProperties config;

	public OAuthAuthorizationInterceptor(AppProperties config) {
		super();
		this.config = config;
	}

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequest) {
		if (!isOAuthEnabled()) {
			return authorizedRule();
		}

		try {
			if (theRequest.getRequestPath().endsWith(Constants.URL_TOKEN_METADATA)) {
				return unauthorizedRule();
			}

			if (isUsingOAuth(theRequest)) {
				return authorizeOAuth(theRequest);
			}
			logger.warn("Authorization failed - no authorization supplied");
		} catch (AuthenticationException e) {
			throw e;
		} catch (RuntimeException e) {
			logger.error("Unexpected exception during authorization", e);
		}

		throw new AuthenticationException("Missing or invalid authorization");
	}

	private boolean isUsingOAuth(RequestDetails theRequest) {
		return isOAuthEnabled() && OAuth2Helper.hasToken(theRequest);
	}

	private List<IAuthRule> authorizedRule() {
		return new RuleBuilder()
			.allowAll()
			.build();
	}

	private List<IAuthRule> unauthorizedRule() {
		// By default, deny everything except the metadata request
		return new RuleBuilder()
			.allow().metadata().andThen()
			.denyAll()
			.build();
	}

	private List<IAuthRule> authorizeOAuth(RequestDetails theRequest) throws AuthenticationException {
		logger.info("Authorizing via OAuth2");
		String token = OAuth2Helper.getToken(theRequest);
		try {
			DecodedJWT jwt = JWT.decode(token);
			String jwksUrl = config.getOauth().getJwks_url();
			OAuth2Helper.verify(jwt, jwksUrl);

			List<String> clientRoles = OAuth2Helper.getClientRoles(jwt, getOAuthClientId());
			if (clientRoles.isEmpty()) {
				logger.warn("Authorization failure - token doesn't have any client roles");
				return unauthorizedRule();
			}

			// The only difference between the admin role and the user role is that the admin role
			// allows DELETE requests. It still needs to enforce a patient claim, if one exists.
			if (theRequest.getRequestType().equals(RequestTypeEnum.DELETE)
					&& !clientRoles.contains(getOAuthAdminRole())) {
				logger.warn("Authorization failure - token doesn't have the admin role required for delete");
				return unauthorizedRule();
			}

				if (isAuditEventRequest(theRequest)) {
					return authorizeAuditEventRequest(theRequest, clientRoles);
				}

			if (clientRoles.contains(getOAuthAdminRole()) || clientRoles.contains(getOAuthUserRole())) {

				String patientId = OAuth2Helper.getClaimAsString(jwt, "patient");
				if (Strings.isNullOrEmpty(patientId)) {
					logger.debug("No patient claim specified in authorization token");
					return authorizedRule();
				} else {
					logger.debug("Patient claim specified in in authorization token; will use patient compartment rules");
					return authorizedInPatientCompartmentRule(theRequest, patientId);
				}
			}

			logger.warn("Authorization failure - token doesn't have the required client roles");
			return unauthorizedRule();
		} catch (GeneralSecurityException e) {
			logger.warn("Authentication failure - unable to verify token signature", e);
			throw new AuthenticationException("Invalid authorization header: token verification failed - " + e.getMessage(), e);
		} catch (AuthenticationException e) {
			throw e;
		} catch (RuntimeException e) {
			logger.warn("Authentication failure - unable to decode token", e);
			throw new AuthenticationException("Invalid authorization header: token parsing failed - " + e.getMessage(), e);
		}
	}

	private List<IAuthRule> authorizeAuditEventRequest(RequestDetails theRequest, List<String> clientRoles) {
		RequestTypeEnum requestType = theRequest.getRequestType();
		boolean isPostSearch = isAuditEventPostSearchRequest(theRequest);
		boolean hasAdminRole = clientRoles.contains(getOAuthAdminRole());
		boolean hasAuditRole = clientRoles.contains(getOAuthAuditRole());
		if (requestType == RequestTypeEnum.POST && !isPostSearch) {
			if (clientRoles.contains(getOAuthUserRole()) || hasAdminRole || hasAuditRole) {
				return authorizedRule();
			}
			logger.warn("Authorization failure - token doesn't have a permitted role for AuditEvent create");
			return unauthorizedRule();
		}

		if (requestType == RequestTypeEnum.GET || isPostSearch) {
			if (hasAdminRole) {
				return authorizedRule();
			}

			if (Strings.isNullOrEmpty(getOAuthAuditRole())) {
				throw new AuthenticationException("OAuth audit role is not configured");
			}

			if (hasAuditRole) {
				return authorizedRule();
			}

			logger.warn("Authorization failure - token doesn't have a role required for AuditEvent read/search");
			return unauthorizedRule();
		}	
			
		logger.warn("Authorization failure - disallowed AuditEvent request type: {}", requestType);
		return unauthorizedRule();
	}

	private boolean isAuditEventRequest(RequestDetails theRequest) {
		return AUDIT_EVENT_RESOURCE.equalsIgnoreCase(theRequest.getResourceName());
	}

	private boolean isAuditEventPostSearchRequest(RequestDetails theRequest) {
		String requestPath = theRequest.getRequestPath();
		return theRequest.getRequestType() == RequestTypeEnum.POST
			&& requestPath != null
			&& requestPath.endsWith(SEARCH_PATH_SUFFIX);
	}

	private List<IAuthRule> authorizedInPatientCompartmentRule(RequestDetails theRequestDetails, String patientId) {
		if (OAuth2Helper.canBeInPatientCompartment(theRequestDetails.getResourceName())) {
			IdType patientIdType = new IdType(PATIENT_RESOURCE, patientId);
			return new RuleBuilder()
				.allow().read().allResources().inCompartment(PATIENT_RESOURCE, patientIdType).andThen()
				.allow().patch().allRequests().andThen()
				.allow().write().allResources().inCompartment(PATIENT_RESOURCE, patientIdType).andThen()
				.allow().delete().allResources().inCompartment(PATIENT_RESOURCE, patientIdType).andThen()
				.allow().transaction().withAnyOperation().andApplyNormalRules().andThen()
				.denyAll()
				.build();
		}
		return authorizedRule();
	}

	private boolean isOAuthEnabled() {
		return config.getOauth().getEnabled();
	}

	private String getOAuthClientId() {
		return config.getOauth().getClient_id();
	}

	private String getOAuthUserRole() {
		return config.getOauth().getUser_role();
	}

	private String getOAuthAdminRole() {
		return config.getOauth().getAdmin_role();
	}

	private String getOAuthAuditRole() {
		return config.getOauth().getAudit_role();
	}
}
