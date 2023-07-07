package ca.uhn.fhir.jpa.starter;

import java.security.PublicKey;
import java.util.List;

import org.apache.commons.lang3.ObjectUtils;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.jpa.starter.util.ApiKeyHelper;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

@Interceptor
public class CustomAuthorizationInterceptor extends AuthorizationInterceptor {
	private static final Logger logger = LoggerFactory.getLogger(CustomAuthorizationInterceptor.class);
	private static final String PATIENT = "Patient";
	private AppProperties config;
	private static PublicKey publicKey = null;

	public CustomAuthorizationInterceptor(AppProperties config) {
		super();
		this.config = config;
	}

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequest) {
		try {

			if (theRequest.getRequestPath().equals(RestOperationTypeEnum.METADATA.getCode())) {
				return allowAll();
			}

			if (!isAuthorizationEnabled()) {
				logger.warn("No authorization methods enabled");
				return allowAll();
			}

			if (isOAuthEnabled() && OAuth2Helper.hasBearerToken(theRequest)) {
				logger.info("Authorizing via OAuth2");
				return authorizeOAuth(theRequest);
			}
			if (isApiKeyEnabled() && ApiKeyHelper.hasApiKey(theRequest)) {
				logger.info("Authorizing via X-API-KEY");
				return authorizeApiKey(theRequest);
			}
			if (isBasicAuthEnabled() && BasicAuthHelper.hasBasicCredentials(theRequest)) {
				logger.info("Authorizing via basic auth");
				return authorizeBasicAuth(theRequest);
			}
		} catch (Exception e) {
			logger.warn("Unexpected authorization error: {}", e.getMessage());
			return denyAll();
		}

		logger.warn("Authorization failure - fall through");
		return denyAll();
	}

	private List<IAuthRule> denyAll() {
		return new RuleBuilder().denyAll().build();
	}

	private List<IAuthRule> allowAll() {
		return new RuleBuilder().allowAll().build();
	}

	private boolean isAuthorizationEnabled() {
		return isOAuthEnabled() || isApiKeyEnabled() || isBasicAuthEnabled();
	}

	private List<IAuthRule> authorizeOAuth(RequestDetails theRequest) {
		String token = OAuth2Helper.getToken(theRequest);

		try {
			DecodedJWT jwt = JWT.decode(token);
			String kid = OAuth2Helper.getJwtKeyId(token);
			if (publicKey == null) {
				publicKey = OAuth2Helper.getJwtPublicKey(kid, config.getOauth().getJwks_url());
			}
			JWTVerifier verifier = OAuth2Helper.getJWTVerifier(jwt, publicKey);
			jwt = verifier.verify(token);
			if (theRequest.getRequestType().equals(RequestTypeEnum.DELETE)) {
			  if (OAuth2Helper.hasClientRole(jwt, getOAuthClientId(), getOAuthAdminRole())) {
			    return allowAll();
			  }
			} else if (OAuth2Helper.hasClientRole(jwt, getOAuthClientId(), getOAuthUserRole())) {
				String patientId = OAuth2Helper.getPatientReferenceFromToken(jwt, "patient");
				if (ObjectUtils.isEmpty(patientId)) {
					return allowAll();
				}
			  	return allowForClaimResourceId(theRequest,patientId);
			}
		} catch (TokenExpiredException e) {
			logger.warn("OAuth2 authentication failure - token has expired");
		} catch (Exception e) {
			logger.warn("Unexpected exception verifying OAuth2 token: {}", e.getMessage());
		}

		logger.warn("OAuth2 authentication failure");
		return denyAll();
	}

	private List<IAuthRule> allowForClaimResourceId(RequestDetails theRequestDetails,String patientId) {
		if (OAuth2Helper.canBeInPatientCompartment(theRequestDetails.getResourceName())) {
			return new RuleBuilder()
				.allow().read().allResources()
					.inCompartment(PATIENT, new IdType(PATIENT, patientId)).andThen()
				.allow().write().allResources()
					.inCompartment(PATIENT, new IdType(PATIENT, patientId)).andThen()
				.allow().transaction().withAnyOperation().andApplyNormalRules().andThen()
				.allow().patch().allRequests().andThen()
				.denyAll()
				.build();
		}
		return allowAll();
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

	private boolean isBasicAuthEnabled() {
		return config.getBasic_auth().getEnabled();
	}

	private String getBasicAuthUsername() {
		return config.getBasic_auth().getUsername();
	}

	private String getBasicAuthPassword() {
		return config.getBasic_auth().getPassword();
	}

	private boolean isApiKeyEnabled() {
		return config.getApikey().getEnabled();
	}

	private String getApiKey() {
		return config.getApikey().getKey();
	}

	private List<IAuthRule> authorizeApiKey(RequestDetails theRequest) {
		if (ApiKeyHelper.isAuthorized(theRequest, getApiKey())) {
			return allowAll();
		}

		logger.warn("API key authorization failure - invalid X-API-KEY specified");
		return denyAll();
	}

	private List<IAuthRule> authorizeBasicAuth(RequestDetails theRequest) {
		String username = getBasicAuthUsername();
		String password = getBasicAuthPassword();
		if (BasicAuthHelper.isAuthorized(theRequest, username, password)) {
			return allowAll();
		}
		logger.warn("Basic authorization failed - invalid credentials specified");
		return denyAll();
	}
}
