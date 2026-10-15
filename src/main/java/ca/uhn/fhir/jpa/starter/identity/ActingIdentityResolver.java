package ca.uhn.fhir.jpa.starter.identity;

import java.util.Collection;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

public final class ActingIdentityResolver {
	private static final Logger logger = LoggerFactory.getLogger(ActingIdentityResolver.class);

	public static final String HEADER = "X-Acting-Identity";
	public static final String NO_PERSON_VALUE = "none";

	private static final String PRACTITIONER = "Practitioner";
	private static final String PATIENT = "Patient";
	private static final String PRACTITIONER_CLAIM = "practitioner";
	private static final String PATIENT_CLAIM = "patient";
	private static final String AZP_CLAIM = "azp";
	private static final Pattern FHIR_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

	private ActingIdentityResolver() {}

	public static ActingIdentity resolve(DecodedJWT jwt, String header, Collection<String> headerClients) {
		Claim practitionerClaim = jwt.getClaim(PRACTITIONER_CLAIM);
		if (hasValue(practitionerClaim)) {
			return orLost(parseReference(practitionerClaim.asString(), PRACTITIONER, false), "practitioner claim");
		}

		Claim patientClaim = jwt.getClaim(PATIENT_CLAIM);
		if (hasValue(patientClaim)) {
			return orLost(parseReference(patientClaim.asString(), PATIENT, true), "patient claim");
		}

		if (StringUtils.isBlank(header)) {
			return ActingIdentity.LOST;
		}

		String azp = claim(jwt, AZP_CLAIM);
		if (azp == null || headerClients == null || !headerClients.contains(azp)) {
			logger.warn("Ignoring the {} header from client {}, which is not allowed to set it", HEADER, azp);
			return ActingIdentity.LOST;
		}

		String value = header.trim();
		if (NO_PERSON_VALUE.equalsIgnoreCase(value)) {
			return ActingIdentity.NO_PERSON;
		}
		ActingIdentity identity = parseReference(value, PRACTITIONER, false);
		if (identity == null) {
			identity = parseReference(value, PATIENT, false);
		}
		return orLost(identity, HEADER + " header");
	}

	private static ActingIdentity orLost(ActingIdentity identity, String source) {
		if (identity == null) {
			logger.warn("Malformed acting identity in the {}; recording it as lost", source);
			return ActingIdentity.LOST;
		}
		return identity;
	}

	private static ActingIdentity parseReference(String value, String resourceType, boolean bareIdAllowed) {
		if (value == null) {
			return null;
		}
		IdType idType;
		try {
			idType = new IdType(value.trim());
		} catch (RuntimeException e) {
			return null;
		}

		String id = idType.getIdPart();
		if (id == null || !FHIR_ID.matcher(id).matches() || idType.hasVersionIdPart()) {
			return null;
		}
		boolean typeMatches = resourceType.equals(idType.getResourceType())
			|| (bareIdAllowed && idType.getResourceType() == null && idType.getBaseUrl() == null);
		if (!typeMatches) {
			return null;
		}

		String baseUrl = StringUtils.trimToNull(idType.getBaseUrl());
		return PRACTITIONER.equals(resourceType)
			? ActingIdentity.practitioner(baseUrl, id)
			: ActingIdentity.patient(baseUrl, id);
	}

	private static boolean hasValue(Claim claim) {
		if (claim == null || claim.isMissing() || claim.isNull()) {
			return false;
		}
		String value = claim.asString();
		return value == null || StringUtils.isNotBlank(value);
	}

	private static String claim(DecodedJWT jwt, String name) {
		Claim claim = jwt.getClaim(name);
		return claim == null ? null : claim.asString();
	}
}
