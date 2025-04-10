package ca.uhn.fhir.jpa.starter.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ObjectUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.MDC;

import java.util.UUID;

@Interceptor
public class CorrelationIdInterceptor {
	private static final String X_CORRELATION_ID = "X-Correlation-Id";

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
	public boolean incomingRequestPreProcessed(HttpServletRequest request, HttpServletResponse response) {
		String corelationId = ObjectUtils.isNotEmpty(request.getHeader(X_CORRELATION_ID))
				? request.getHeader(X_CORRELATION_ID)
				: UUID.randomUUID().toString();
		MDC.put(X_CORRELATION_ID, corelationId);
		return true;
	}

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public boolean outgoingResponse(
			RequestDetails requestDetails,
			ServletRequestDetails servletRequestDetails,
			IBaseResource resource,
			ResponseDetails responseDetails,
			HttpServletRequest request,
			HttpServletResponse response) {
		response.addHeader(X_CORRELATION_ID, MDC.get(X_CORRELATION_ID));
		return true;
	}

	@Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
	public void processingCompleted(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
		MDC.remove(X_CORRELATION_ID);
	}
}
