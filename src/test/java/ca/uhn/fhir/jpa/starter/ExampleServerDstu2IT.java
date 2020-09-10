package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class, properties =
  {
     "spring.batch.job.enabled=false",
     "hapi.fhir.fhir_version=dstu2",
     "spring.datasource.url=jdbc:h2:mem:dbr2",
  })
public class ExampleServerDstu2IT {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ExampleServerDstu2IT.class);
	private IGenericClient ourClient;
	private FhirContext ourCtx;

  @LocalServerPort
  private int port;

  @Test
	void testCreateAndRead() {

		String methodName = "testCreateResourceConditional";

		Patient pt = new Patient();
		pt.addName().addFamily(methodName);
		IIdType id = ourClient.create().resource(pt).execute().getId();
		Patient pt2 = ourClient.read().resource(Patient.class).withId(id).execute();
		assertEquals(methodName, pt2.getName().get(0).getFamily().get(0).getValue());
	}

	@AfterAll
	public static void afterClass() throws Exception {
		ourServer.stop();
	}

	@BeforeAll
	public static void beforeClass() throws Exception {
		String path = Paths.get("").toAbsolutePath().toString();

		ourLog.info("Project base path is: {}", path);

		ourServer = new Server(0);

		WebAppContext webAppContext = new WebAppContext();
		webAppContext.setContextPath("/hapi-fhir-jpaserver");
		webAppContext.setDescriptor(path + "/src/main/webapp/WEB-INF/web.xml");
		webAppContext.setResourceBase(path + "/target/hapi-fhir-jpaserver-starter");
		webAppContext.setParentLoaderPriority(true);

		ourServer.setHandler(webAppContext);
		ourServer.start();

		ourPort = JettyUtil.getPortForStartedServer(ourServer);

    ourCtx = FhirContext.forDstu2();
		ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		String ourServerBase = "http://localhost:" + port + "/fhir/";
		ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
		ourClient.registerInterceptor(new LoggingInterceptor(true));
	}
}
