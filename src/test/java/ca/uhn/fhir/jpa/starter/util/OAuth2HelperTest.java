package ca.uhn.fhir.jpa.starter.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.IncorrectClaimException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

class OAuth2HelperTest {

	private static final String ISSUER = "https://auth-qa.elimuinformatics.com/auth/realms/product";

	private static KeyPair key1;
	private static KeyPair key2;

	@BeforeAll
	static void generateKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		key1 = generator.generateKeyPair();
		key2 = generator.generateKeyPair();
	}

	@Test
	void validTokenIsAccepted() {
		DecodedJWT jwt = token(key1, "kid-1", ISSUER);
		assertDoesNotThrow(() -> OAuth2Helper.verify(jwt, provider(Map.of("kid-1", jwk("kid-1", key1, "sig"))), ISSUER));
	}

	@Test
	void wrongIssuerIsRejected() {
		DecodedJWT jwt = token(key1, "kid-1", "https://auth-stage.elimuinformatics.com/auth/realms/product");
		JwkProvider provider = provider(Map.of("kid-1", jwk("kid-1", key1, "sig")));
		assertThrows(IncorrectClaimException.class, () -> OAuth2Helper.verify(jwt, provider, ISSUER));
	}

	@Test
	void unknownKidIsRejectedAsSecurityFailure() {
		DecodedJWT jwt = token(key1, "kid-unknown", ISSUER);
		JwkProvider provider = provider(Map.of("kid-1", jwk("kid-1", key1, "sig")));
		GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () -> OAuth2Helper.verify(jwt, provider, ISSUER));
		assertInstanceOf(SigningKeyNotFoundException.class, e.getCause());
	}

	@Test
	void missingKidIsRejected() {
		DecodedJWT jwt = token(key1, null, ISSUER);
		JwkProvider provider = provider(Map.of("kid-1", jwk("kid-1", key1, "sig")));
		assertThrows(GeneralSecurityException.class, () -> OAuth2Helper.verify(jwt, provider, ISSUER));
	}

	@Test
	void encryptionKeyIsNotAcceptedForSignatures() {
		DecodedJWT jwt = token(key1, "kid-enc", ISSUER);
		JwkProvider provider = provider(Map.of("kid-enc", jwk("kid-enc", key1, "enc")));
		assertThrows(GeneralSecurityException.class, () -> OAuth2Helper.verify(jwt, provider, ISSUER));
	}

	@Test
	void tokenSignedByAnotherKeyUnderAKnownKidIsRejected() {
		DecodedJWT jwt = token(key2, "kid-1", ISSUER);
		JwkProvider provider = provider(Map.of("kid-1", jwk("kid-1", key1, "sig")));
		assertThrows(SignatureVerificationException.class, () -> OAuth2Helper.verify(jwt, provider, ISSUER));
	}

	@Test
	void rotatedKeyUnderNewKidIsAcceptedAfterTheOldOne() {
		JwkProvider provider = provider(Map.of("kid-1", jwk("kid-1", key1, "sig"), "kid-2", jwk("kid-2", key2, "sig")));
		assertDoesNotThrow(() -> OAuth2Helper.verify(token(key1, "kid-1", ISSUER), provider, ISSUER));
		assertDoesNotThrow(() -> OAuth2Helper.verify(token(key2, "kid-2", ISSUER), provider, ISSUER));
	}

	@Test
	void eachVerificationResolvesTheTokensOwnKid() throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		Map<String, Jwk> keys = Map.of("kid-1", jwk("kid-1", key1, "sig"), "kid-2", jwk("kid-2", key2, "sig"));
		JwkProvider provider = kid -> {
			lookups.incrementAndGet();
			return provider(keys).get(kid);
		};
		OAuth2Helper.verify(token(key1, "kid-1", ISSUER), provider, ISSUER);
		OAuth2Helper.verify(token(key2, "kid-2", ISSUER), provider, ISSUER);
		assertThrows(SignatureVerificationException.class,
				() -> OAuth2Helper.verify(token(key1, "kid-2", ISSUER), provider, ISSUER));
		assertEquals(3, lookups.get());
	}

	private static DecodedJWT token(KeyPair key, String kid, String issuer) {
		Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) key.getPublic(), (RSAPrivateKey) key.getPrivate());
		return JWT.decode(JWT.create().withKeyId(kid).withIssuer(issuer).withSubject("subject").sign(algorithm));
	}

	private static JwkProvider provider(Map<String, Jwk> keys) {
		return kid -> {
			Jwk jwk = keys.get(kid);
			if (jwk == null) {
				throw new SigningKeyNotFoundException("No key found for kid " + kid, null);
			}
			return jwk;
		};
	}

	private static Jwk jwk(String kid, KeyPair key, String use) {
		RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
		Map<String, Object> values = new HashMap<>();
		values.put("kid", kid);
		values.put("kty", "RSA");
		values.put("alg", "sig".equals(use) ? "RS256" : "RSA-OAEP");
		values.put("use", use);
		values.put("n", base64Url(publicKey.getModulus()));
		values.put("e", base64Url(publicKey.getPublicExponent()));
		return Jwk.fromValues(values);
	}

	private static String base64Url(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes[0] == 0) {
			bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
