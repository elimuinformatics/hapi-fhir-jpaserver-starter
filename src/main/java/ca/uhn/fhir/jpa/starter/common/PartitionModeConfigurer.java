package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.interceptor.PatientIdPartitionInterceptor;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.partition.PartitionManagementProvider;
import ca.uhn.fhir.jpa.searchparam.extractor.ISearchParamExtractor;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.partition.RequestTenantPartitionInterceptor;
import ca.uhn.fhir.rest.server.tenant.UrlBaseTenantIdentificationStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class PartitionModeConfigurer {
	private static final Logger ourLog = LoggerFactory.getLogger(PartitionModeConfigurer.class);

	@Autowired
	private AppProperties myAppProperties;

	@Autowired
	private FhirContext myFhirContext;

	@Autowired
	private ISearchParamExtractor mySearchParamExtractor;

	@Autowired
	private PartitionSettings myPartitionSettings;

	@Autowired
	private RestfulServer myRestfulServer;

	@Autowired
	private IInterceptorService myInterceptorService;

	@Autowired
	private PartitionManagementProvider myPartitionManagementProvider;

	@PostConstruct
	public void start() {
		if (myAppProperties.getPartitioning() != null) {
			if (myAppProperties.getPartitioning().getPatient_id_partitioning_mode() == Boolean.TRUE) {
				ourLog.info("Partitioning mode enabled in: Patient ID partitioning mode");
				PatientIdPartitionInterceptor patientIdInterceptor =
						new PatientIdPartitionInterceptor(myFhirContext, mySearchParamExtractor, myPartitionSettings);
				myRestfulServer.registerInterceptor(patientIdInterceptor);
				myPartitionSettings.setUnnamedPartitionMode(true);
			} else if (myAppProperties.getPartitioning().getRequest_tenant_partitioning_mode() == Boolean.TRUE) {
				ourLog.info("Partitioning mode enabled in: Request tenant partitioning mode");
				RequestTenantPartitionInterceptor tenantInterceptor = new RequestTenantPartitionInterceptor();
				myRestfulServer.registerInterceptor(tenantInterceptor);
				// Also register on the global interceptor service so batch2 background
				// workers (bulk export, reindex, etc.) can resolve partitions — they
				// consult the global IInterceptorBroadcaster, not the RestfulServer registry.
				myInterceptorService.registerInterceptor(tenantInterceptor);
				myRestfulServer.setTenantIdentificationStrategy(new UrlBaseTenantIdentificationStrategy());
			}

			myRestfulServer.registerProviders(myPartitionManagementProvider);
		}
	}
}
