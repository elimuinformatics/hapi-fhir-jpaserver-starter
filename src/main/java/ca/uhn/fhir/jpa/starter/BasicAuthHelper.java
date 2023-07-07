package ca.uhn.fhir.jpa.starter;

import java.util.Base64;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.http.HttpHeaders;
import ca.uhn.fhir.rest.api.server.RequestDetails;

public class BasicAuthHelper {

  private static final String BASIC_PREFIX = "BASIC ";

  private BasicAuthHelper() {}

  public static boolean isAuthorized(RequestDetails theRequest, String username, String password) {
    String credentials = theRequest
      .getHeader(HttpHeaders.AUTHORIZATION)
      .substring(BASIC_PREFIX.length());
    String encodedCredentials = Base64
      .getEncoder()
      .encodeToString((username + ":" + password)
      .getBytes());
    return encodedCredentials.equals(credentials);
  }

	public static boolean hasBasicCredentials(RequestDetails theRequest) {
		String auth = theRequest.getHeader(HttpHeaders.AUTHORIZATION);
		return (!ObjectUtils.isEmpty(auth) && auth.toUpperCase().startsWith(BASIC_PREFIX));
	}
}
