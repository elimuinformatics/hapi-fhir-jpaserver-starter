package ca.uhn.fhir.jpa.starter.interceptor;

import java.util.UUID;

import org.apache.commons.lang3.ObjectUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.MDC;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Interceptor
public class LoggerInterceptor {
  private static final String X_CORRELATION_ID = "X-Correlation-Id";
  private static final String CORRELATION_ID = "correlationId";
  private static final String X_LAUNCH_ID = "X-Launch-Id";
  private static final String X_APP_NAME = "X-App-Name";
  private static final String LAUNCH_ID = "launchId";
  private static final String APP = "app";


  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
  public boolean incomingRequestPreProcessed(HttpServletRequest request,
      HttpServletResponse response) {
	 String correlationId = ObjectUtils.isNotEmpty(request.getHeader(X_CORRELATION_ID))
        ? request.getHeader(X_CORRELATION_ID)
        : UUID.randomUUID().toString();
	MDC.put(CORRELATION_ID, correlationId);
		putIfPresent(request, X_LAUNCH_ID, LAUNCH_ID);
		putIfPresent(request, X_APP_NAME, APP);

		return true;
  }

	private void putIfPresent(HttpServletRequest request, String headerName, String mdcKey) {
		String headerValue = request.getHeader(headerName);
		if (ObjectUtils.isNotEmpty(headerValue)) {
			MDC.put(mdcKey, headerValue);
		}
	}

  @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
  public boolean outgoingResponse(RequestDetails requestDetails,
      ServletRequestDetails servletRequestDetails, IBaseResource resource, ResponseDetails responseDetails,
      HttpServletRequest request, HttpServletResponse response) {
    response.addHeader(CORRELATION_ID, MDC.get(CORRELATION_ID));
	  String launchId = MDC.get(LAUNCH_ID);
	  if (launchId != null) {
		  response.addHeader(LAUNCH_ID, launchId);
	  }
	  return true;
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
  public void processingCompleted(RequestDetails requestDetails,
      ServletRequestDetails servletRequestDetails) {
	  MDC.remove(CORRELATION_ID);
	  MDC.remove(LAUNCH_ID);
	  MDC.remove(APP);
  }
}
