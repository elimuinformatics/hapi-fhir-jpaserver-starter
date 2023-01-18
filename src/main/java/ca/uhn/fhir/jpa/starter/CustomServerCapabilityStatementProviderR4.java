package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestSecurityComponent;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.UriType;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;


public class CustomServerCapabilityStatementProviderR4 extends ServerCapabilityStatementProvider {

	
	public CustomServerCapabilityStatementProviderR4(RestfulServer theServer) {
		super(theServer);
		// TODO Auto-generated constructor stub
	}
	
	private static final String OAUTH_TOKEN_URL = System.getenv("OAUTH_TOKEN_URL");
	private static final String OAUTH_MANAGE_URL = System.getenv("OAUTH_MANAGE_URL");
	
	private CapabilityStatement capabilityStatement;
	
	
	public CustomServerCapabilityStatementProviderR4(@Nonnull RestfulServer theRestfulServer, @Nonnull IFhirSystemDao<Bundle, Meta> theSystemDao, @Nonnull DaoConfig theDaoConfig, @Nonnull ISearchParamRegistry theSearchParamRegistry) {
		super(theRestfulServer, theSystemDao, theDaoConfig, theSearchParamRegistry);
	}
	
	
	@Override
	public IBaseConformance getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
		// TODO Auto-generated method stub
		capabilityStatement =  (CapabilityStatement) super.getServerConformance(theRequest, theRequestDetails);
		capabilityStatement.getRest().get(0).setSecurity(getSecurityComponent());
		return capabilityStatement;
	}

	private static CapabilityStatementRestSecurityComponent getSecurityComponent() {
		CapabilityStatementRestSecurityComponent security = new CapabilityStatementRestSecurityComponent();
		List<Extension> extensions = new ArrayList<Extension>();
		extensions.add(new Extension("token", new UriType(OAUTH_TOKEN_URL)));
		extensions.add(new Extension("manage", new UriType(OAUTH_MANAGE_URL)));
		List<Extension> extensionsList = new ArrayList<Extension>();
		extensionsList.add((Extension) new Extension(
				new UriType("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris"))
						.setExtension(extensions));
		security.setExtension(extensionsList);
		return security;
	}
}
