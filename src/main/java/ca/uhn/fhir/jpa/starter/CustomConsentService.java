package ca.uhn.fhir.jpa.starter;

import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Service;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentService;

@Service
public class CustomConsentService implements IConsentService {

  private static final String CLAIM_NAME = System.getenv("claim_name");

  private OAuth2Helper oAuth2Helper = new OAuth2Helper();

  @Override
  public ConsentOutcome startOperation(RequestDetails theRequestDetails,
      IConsentContextServices theContextServices) {
    RequestTypeEnum requestType = theRequestDetails.getRequestType();
    if (requestType.equals(RequestTypeEnum.POST) || requestType.equals(RequestTypeEnum.PUT)) {
      return validateRequest(theRequestDetails);
    } else if (requestType.equals(RequestTypeEnum.GET)
        || requestType.equals(RequestTypeEnum.DELETE)) {
      return ConsentOutcome.PROCEED;
    }
    return ConsentOutcome.REJECT;
  }

  @Override
  public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource,
      IConsentContextServices theContextServices) {
    return ConsentOutcome.PROCEED;
  }

  @Override
  public ConsentOutcome willSeeResource(RequestDetails theRequestDetails, IBaseResource theResource,
      IConsentContextServices theContextServices) {
    RequestTypeEnum requestType = theRequestDetails.getRequestType();
    if (requestType.equals(RequestTypeEnum.GET)
        && theRequestDetails.getRequestPath().matches(".*/.*")) {
      return validateResponse(theRequestDetails, theResource);
    }
    return ConsentOutcome.PROCEED;
  }

  private String getPatientFromToken(RequestDetails theRequestDetails) {
    String token = theRequestDetails.getHeader("Authorization");
    if (token != null) {
      token = token.substring(CustomAuthorizationInterceptor.getTokenPrefix().length());
      DecodedJWT jwt = JWT.decode(token);
      String patRefId = oAuth2Helper.getPatientReferenceFromToken(jwt, CLAIM_NAME);
      return patRefId;
    }
    return null;
  }

  private ConsentOutcome validateRequest(RequestDetails theRequestDetails) {
    String patientId = getPatientFromToken(theRequestDetails);
    if (patientId != null) {
      String patientRef = "Patient/" + patientId;
      Resource resource = (Resource) theRequestDetails.getResource();
      if (resource instanceof Patient) {
        return ConsentOutcome.PROCEED;
      }
      return isValidReferencePresent(patientRef, resource);
    }
    return ConsentOutcome.PROCEED;
  }

  private ConsentOutcome validateResponse(RequestDetails theRequestDetails,
      IBaseResource theResource) {
    String patientId = getPatientFromToken(theRequestDetails);
    if (patientId != null) {
      String patientRef = "Patient/" + patientId;
      if (theResource instanceof Patient) {
        Patient pts = (Patient) theResource;
        if (String.valueOf(pts.getIdElement().getIdPartAsLong()).equals(patientId)) {
          return ConsentOutcome.PROCEED;
        }
        return ConsentOutcome.REJECT;
      }
      Resource resource = (Resource) theResource;
      return isValidReferencePresent(patientRef, resource);
    }
    return ConsentOutcome.PROCEED;
  }

  private ConsentOutcome isValidReferencePresent(String patientRef, Resource resource) {
    List<Base> subjectValue = resource.getNamedProperty("subject").getValues();
    if (!subjectValue.isEmpty()) {
      Reference ref = (Reference) subjectValue.get(0);
      if (ref.getReference().equals(patientRef)) {
        return ConsentOutcome.PROCEED;
      }
    }
    return ConsentOutcome.REJECT;
  }

}
