package ca.uhn.fhir.jpa.starter.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.jpa.starter.identity.ActingIdentity.State;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ActingIdentityResolverTest {

	private static final List<String> HEADER_CLIENTS = List.of("omnibus-api", "optout");

	private Logger myLogger;
	private ListAppender<ILoggingEvent> myAppender;

	@BeforeEach
	void setUp() {
		myLogger = (Logger) LoggerFactory.getLogger(ActingIdentityResolver.class);
		myAppender = new ListAppender<>();
		myAppender.start();
		myLogger.addAppender(myAppender);
	}

	@AfterEach
	void tearDown() {
		myLogger.detachAppender(myAppender);
	}

	@Test
	void practitionerClaim_resolvesToPractitioner() {
		ActingIdentity identity = resolve(token(Map.of("azp", "sapphire", "practitioner", "Practitioner/abc")), null);

		assertEquals(State.PRACTITIONER, identity.getState());
		assertEquals("Practitioner/abc", identity.getReference());
		assertNull(identity.getBaseUrl());
	}

	@Test
	void absolutePractitionerClaim_keepsTheBaseUrl() {
		ActingIdentity identity = resolve(
			token(Map.of("azp", "sapphire", "practitioner", "https://ehr.example.org/fhir/R4/Practitioner/abc")), null);

		assertEquals(State.PRACTITIONER, identity.getState());
		assertEquals("Practitioner/abc", identity.getReference());
		assertEquals("https://ehr.example.org/fhir/R4", identity.getBaseUrl());
	}

	@Test
	void patientClaim_resolvesToPatient() {
		ActingIdentity identity = resolve(token(Map.of("azp", "engage", "patient", "456")), null);

		assertEquals(State.PATIENT, identity.getState());
		assertEquals("Patient/456", identity.getReference());
	}

	@Test
	void practitionerAndPatientClaims_practitionerWins() {
		ActingIdentity identity = resolve(
			token(Map.of("azp", "sapphire", "practitioner", "Practitioner/abc", "patient", "456")), null);

		assertEquals("Practitioner/abc", identity.getReference());
	}

	@Test
	void claimOverridesHeaderFromAllowlistedClient() {
		ActingIdentity identity = resolve(token(Map.of("azp", "omnibus-api", "patient", "456")), "Practitioner/other");

		assertEquals("Patient/456", identity.getReference());
	}

	@Test
	void practitionerHeaderFromAllowlistedClient_resolvesToPractitioner() {
		ActingIdentity identity = resolve(token(Map.of("azp", "omnibus-api")), "Practitioner/abc");

		assertEquals(State.PRACTITIONER, identity.getState());
		assertEquals("Practitioner/abc", identity.getReference());
	}

	@Test
	void patientHeaderFromAllowlistedClient_resolvesToPatient() {
		ActingIdentity identity = resolve(token(Map.of("azp", "optout")), " Patient/456 ");

		assertEquals(State.PATIENT, identity.getState());
		assertEquals("Patient/456", identity.getReference());
	}

	@Test
	void noPersonHeaderFromAllowlistedClient_resolvesToNoPerson() {
		assertEquals(ActingIdentity.NO_PERSON, resolve(token(Map.of("azp", "omnibus-api")), "none"));
	}

	@Test
	void allowlistedClientWithoutHeader_resolvesToLost() {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of("azp", "omnibus-api")), null));
	}

	@Test
	void userTokenWithoutClaimOrHeader_resolvesToLost() {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of("azp", "sapphire")), null));
	}

	@Test
	void headerFromNonAllowlistedClient_isIgnoredAndLogged() {
		ActingIdentity identity = resolve(token(Map.of("azp", "sapphire")), "Practitioner/abc");

		assertEquals(ActingIdentity.LOST, identity);
		assertTrue(myAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
			&& event.getFormattedMessage().contains("from client sapphire")
			&& !event.getFormattedMessage().contains("Practitioner/abc")));
	}

	@Test
	void noPersonHeaderFromNonAllowlistedClient_resolvesToLost() {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of("azp", "sapphire")), "none"));
	}

	@Test
	void headerFromTokenWithoutAzp_isIgnored() {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of()), "Practitioner/abc"));
	}

	@Test
	void emptyAllowlist_neverHonorsTheHeader() {
		assertEquals(ActingIdentity.LOST,
			ActingIdentityResolver.resolve(token(Map.of("azp", "omnibus-api")), "Practitioner/abc", List.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"Observation/1", "Practitioner/", "123", "garbage value", "Practitioner/a b",
		"Patient/0123456789012345678901234567890123456789012345678901234567890123456789",
		"Practitioner/abc/_history/2"})
	void malformedHeaderFromAllowlistedClient_resolvesToLost(String header) {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of("azp", "omnibus-api")), header));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "Patient/abc", "Practitioner/", "Practitioner/abc/_history/2"})
	void malformedPractitionerClaim_resolvesToLost(String claim) {
		assertEquals(ActingIdentity.LOST,
			resolve(token(Map.of("azp", "omnibus-api", "practitioner", claim)), "Practitioner/fromHeader"));
	}

	@Test
	void nonStringPractitionerClaim_resolvesToLostAndIgnoresTheHeader() {
		DecodedJWT jwt = JWT.decode(JWT.create()
			.withClaim("azp", "omnibus-api")
			.withArrayClaim("practitioner", new String[] {"Practitioner/abc"})
			.sign(Algorithm.HMAC256("test-secret")));

		assertEquals(ActingIdentity.LOST, resolve(jwt, "Practitioner/fromHeader"));
	}

	@Test
	void nonStringPatientClaim_resolvesToLostAndIgnoresTheHeader() {
		DecodedJWT jwt = JWT.decode(JWT.create()
			.withClaim("azp", "omnibus-api")
			.withClaim("patient", 456)
			.sign(Algorithm.HMAC256("test-secret")));

		assertEquals(ActingIdentity.LOST, resolve(jwt, "Patient/fromHeader"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"practitioner", "patient"})
	void blankClaim_resolvesToLostAndIgnoresTheHeader(String claimName) {
		assertEquals(ActingIdentity.LOST, resolve(token(Map.of("azp", "omnibus-api", claimName, " ")), "none"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"practitioner", "patient"})
	void nullClaim_resolvesToLostAndIgnoresTheHeader(String claimName) {
		DecodedJWT jwt = JWT.decode(JWT.create()
			.withClaim("azp", "omnibus-api")
			.withNullClaim(claimName)
			.sign(Algorithm.HMAC256("test-secret")));

		assertEquals(ActingIdentity.LOST, resolve(jwt, "none"));
	}

	@Test
	void blankPractitionerClaim_resolvesToLostDespiteAValidPatientClaim() {
		assertEquals(ActingIdentity.LOST,
			resolve(token(Map.of("azp", "omnibus-api", "practitioner", "", "patient", "456")), null));
	}

	@Test
	void from_requestWithoutIdentity_isLost() {
		assertEquals(ActingIdentity.LOST, ActingIdentity.from(new SystemRequestDetails()));
		assertEquals(ActingIdentity.LOST, ActingIdentity.from(null));
	}

	@Test
	void from_requestWithIdentity_returnsIt() {
		SystemRequestDetails request = new SystemRequestDetails();
		request.getUserData().put(ActingIdentity.USER_DATA_KEY, ActingIdentity.NO_PERSON);

		assertEquals(ActingIdentity.NO_PERSON, ActingIdentity.from(request));
	}

	private static ActingIdentity resolve(DecodedJWT jwt, String header) {
		return ActingIdentityResolver.resolve(jwt, header, HEADER_CLIENTS);
	}

	private static DecodedJWT token(Map<String, String> claims) {
		JWTCreator.Builder builder = JWT.create().withIssuer("https://auth.example.com/auth/realms/product");
		claims.forEach(builder::withClaim);
		return JWT.decode(builder.sign(Algorithm.HMAC256("test-secret")));
	}
}
