package ca.uhn.fhir.jpa.starter.util;

import java.net.MalformedURLException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.http.HttpHeaders;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;

public class OAuth2Helper {
	private static final String BEARER_PREFIX = "BEARER ";
	private static final Map<String, JwkProvider> JWK_PROVIDERS = new ConcurrentHashMap<>();

	private OAuth2Helper() {}

	public static String getToken(RequestDetails theRequest) {
		Validate.notNull(theRequest, "theRequest must not be null");
		String auth = theRequest.getHeader(HttpHeaders.AUTHORIZATION);
		return auth.substring(BEARER_PREFIX.length());
	}

	public static void verify(DecodedJWT jwt, String jwksUrl, String issuer)
			throws GeneralSecurityException, JWTVerificationException {
		Validate.notBlank(jwksUrl, "jwksUrl must not be blank");
		verify(jwt, JWK_PROVIDERS.computeIfAbsent(jwksUrl, OAuth2Helper::buildJwkProvider), issuer);
	}

	static void verify(DecodedJWT jwt, JwkProvider jwkProvider, String issuer)
			throws GeneralSecurityException, JWTVerificationException {
		Validate.notNull(jwt, "jwt must not be null");
		Validate.notBlank(issuer, "issuer must not be blank");
		String kid = jwt.getKeyId();
		if (kid == null || kid.isBlank()) {
			throw new GeneralSecurityException("Token has no kid");
		}
		Object publicKey;
		try {
			Jwk jwk = jwkProvider.get(kid);
			if (jwk.getUsage() != null && !"sig".equals(jwk.getUsage())) {
				throw new GeneralSecurityException("Key " + kid + " is not a signing key");
			}
			publicKey = jwk.getPublicKey();
		} catch (JwkException e) {
			throw new GeneralSecurityException("Unable to resolve signing key " + kid, e);
		}
		JWTVerifier verifier = JWT.require(getAlgorithm(jwt, publicKey)).withIssuer(issuer).build();
		verifier.verify(jwt);
	}

	private static JwkProvider buildJwkProvider(String jwksUrl) {
		try {
			return new JwkProviderBuilder(URI.create(jwksUrl).toURL())
					.cached(10, 24, TimeUnit.HOURS)
					.rateLimited(10, 1, TimeUnit.MINUTES)
					.build();
		} catch (MalformedURLException | IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid jwksUrl: " + jwksUrl, e);
		}
	}

	public static List<String> getClientRoles(DecodedJWT jwt, String clientId) {
		Validate.notNull(jwt, "jwt must not be null");
		Validate.notBlank(clientId, "clientId must not be blank");
		Claim claim = jwt.getClaim("resource_access");
		HashMap<String, HashMap<String, ArrayList<String>>> resources = claim.as(HashMap.class);
		HashMap<String, ArrayList<String>> clientMap = resources.getOrDefault(clientId, new HashMap<String, ArrayList<String>>());
		return clientMap.getOrDefault("roles", new ArrayList<String>());
	}

	public static String getClaimAsString(RequestDetails theRequest, String name) {
		Validate.notNull(theRequest, "theRequest must not be null");
		Validate.notBlank(name, "name must not be blank");
		String token = getToken(theRequest);
		DecodedJWT jwt = JWT.decode(token);
		return getClaimAsString(jwt, name);
	}

	public static String getClaimAsString(DecodedJWT jwt, String name) {
		Validate.notNull(jwt, "jwt must not be null");
		Validate.notBlank(name, "name must not be blank");
		return jwt.getClaim(name).asString();
	}

	public static boolean canBeInPatientCompartment(String resourceName) {
		Validate.notBlank(resourceName, "resourceName must not be blank");
		/*
		 * For Bundle Request resourceType would be null.
		 * For now we allow all bundle operations this will apply normal rules from authorization intercepter
		 */
		if (ObjectUtils.isEmpty(resourceName)) {
			return true;
		}
		FhirContext ctx = FhirContext.forR4();
		RuntimeResourceDefinition data = ctx.getResourceDefinition(resourceName);
		List<RuntimeSearchParam> compartmentList = data.getSearchParamsForCompartmentName("Patient");
		return !compartmentList.isEmpty();
	}

	public static boolean hasToken(RequestDetails theRequest) {
		Validate.notNull(theRequest, "theRequest must not be null");
		String token = theRequest.getHeader(HttpHeaders.AUTHORIZATION);
		return (!ObjectUtils.isEmpty(token) && token.toUpperCase().startsWith(BEARER_PREFIX));
	}

	private static Algorithm getAlgorithm(DecodedJWT jwt, Object publicKey) throws NoSuchAlgorithmException {
		Validate.notNull(jwt, "jwt must not be null");
		Validate.notNull(publicKey, "publicKey must not be null");
		String alg = jwt.getAlgorithm();
		switch (alg) {
			case "HS256":
				return Algorithm.HMAC256((String)publicKey);
			case "HS384":
				return Algorithm.HMAC384((String)publicKey);
			case "HS512":
				return Algorithm.HMAC512((String)publicKey);
			case "RS256":
				return Algorithm.RSA256((RSAPublicKey)publicKey, null);
			case "RS384":
				return Algorithm.RSA384((RSAPublicKey)publicKey, null);
			case "RS512":
				return Algorithm.RSA512((RSAPublicKey)publicKey, null);
			case "ES256":
				return Algorithm.ECDSA256((ECPublicKey)publicKey, null);
			case "ES384":
				return Algorithm.ECDSA384((ECPublicKey)publicKey, null);
			case "ES512":
				return Algorithm.ECDSA512((ECPublicKey)publicKey, null);
			case "PS256":
			case "PS384":
			default:
				throw new NoSuchAlgorithmException("Algorithm is not supported by this library.");
		}
	}
}
