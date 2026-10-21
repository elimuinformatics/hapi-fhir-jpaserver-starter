package ca.uhn.fhir.jpa.starter.identity;

import java.util.Objects;

import ca.uhn.fhir.rest.api.server.RequestDetails;

/**
 * The person whose action caused a request, as the server resolved it. A request with no stored
 * identity reads as {@link State#LOST}, never as {@link State#NO_PERSON}: only a caller can say that
 * no person was involved.
 */
public final class ActingIdentity {

	public static final String USER_DATA_KEY = ActingIdentity.class.getName();

	public enum State {
		PRACTITIONER,
		PATIENT,
		NO_PERSON,
		LOST
	}

	public static final ActingIdentity NO_PERSON = new ActingIdentity(State.NO_PERSON, null, null);
	public static final ActingIdentity LOST = new ActingIdentity(State.LOST, null, null);

	private final State state;
	private final String baseUrl;
	private final String id;

	private ActingIdentity(State state, String baseUrl, String id) {
		this.state = state;
		this.baseUrl = baseUrl;
		this.id = id;
	}

	static ActingIdentity practitioner(String baseUrl, String id) {
		return new ActingIdentity(State.PRACTITIONER, baseUrl, id);
	}

	static ActingIdentity patient(String baseUrl, String id) {
		return new ActingIdentity(State.PATIENT, baseUrl, id);
	}

	public static ActingIdentity from(RequestDetails theRequest) {
		Object identity = theRequest == null ? null : theRequest.getUserData().get(USER_DATA_KEY);
		return identity instanceof ActingIdentity ? (ActingIdentity) identity : LOST;
	}

	public State getState() {
		return state;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getId() {
		return id;
	}

	public String getReference() {
		switch (state) {
			case PRACTITIONER:
				return "Practitioner/" + id;
			case PATIENT:
				return "Patient/" + id;
			default:
				return null;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ActingIdentity)) {
			return false;
		}
		ActingIdentity other = (ActingIdentity) o;
		return state == other.state && Objects.equals(baseUrl, other.baseUrl) && Objects.equals(id, other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(state, baseUrl, id);
	}

	@Override
	public String toString() {
		String reference = getReference();
		return reference == null ? state.name() : state.name() + "(" + reference + ")";
	}
}
