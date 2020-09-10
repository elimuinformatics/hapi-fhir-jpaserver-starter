package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.test.utilities.JettyUtil;
import ca.uhn.fhir.util.BundleUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ca.uhn.fhir.util.TestUtil.waitForSize;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class, properties = {
		"spring.batch.job.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:dbr4",
		"hapi.fhir.enable_repository_validating_interceptor=true",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.subscription.websocket_enabled=true",
		"hapi.fhir.mdm_enabled=true",
		// Override is currently required when using MDM as the construction of the MDM
		// beans are ambiguous as they are constructed multiple places. This is evident
		// when running in a spring boot environment
		"spring.main.allow-bean-definition-overriding=true" })
class ExampleServerR4IT {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ExampleServerR4IT.class);
	private IGenericClient ourClient;
	private FhirContext ourCtx;

	@LocalServerPort
	private int port;

    static {
        HapiProperties.forceReload();
        HapiProperties.setProperty(HapiProperties.DATASOURCE_URL, "jdbc:h2:mem:dbr4");
        HapiProperties.setProperty(HapiProperties.FHIR_VERSION, "R4");
        HapiProperties.setProperty(HapiProperties.SUBSCRIPTION_WEBSOCKET_ENABLED, "true");
        HapiProperties.setProperty(HapiProperties.EMPI_ENABLED, "true");
        ourCtx = FhirContext.forR4();
    }

		Patient pt = new Patient();
		pt.setActive(true);
		pt.getBirthDateElement().setValueAsString("2020-01-01");
		pt.addIdentifier().setSystem("http://foo").setValue("12345");
		pt.addName().setFamily(methodName);
		IIdType id = ourClient.create().resource(pt).execute().getId();

        Patient pt = new Patient();
        pt.setActive(true);
        pt.getBirthDateElement().setValueAsString("2020-01-01");
        pt.addIdentifier().setSystem("http://foo").setValue("12345");
        pt.addName().setFamily(methodName);
        IIdType id = ourClient.create().resource(pt).execute().getId();

        Patient pt2 = ourClient.read().resource(Patient.class).withId(id).execute();
        assertEquals(methodName, pt2.getName().get(0).getFamily());

        // Test EMPI

        // Wait until the EMPI message has been processed
        await().until(() -> getPeople().size() > 0);
        List<Person> persons = getPeople();

        // Verify a Person was created that links to our Patient
        Optional<String> personLinkToCreatedPatient = persons.stream()
          .map(Person::getLink)
          .flatMap(Collection::stream)
          .map(Person.PersonLinkComponent::getTarget)
          .map(Reference::getReference)
          .filter(pid -> id.toUnqualifiedVersionless().getValue().equals(pid))
          .findAny();
        assertTrue(personLinkToCreatedPatient.isPresent());
    }

  private List<Person> getPeople() {
    Bundle bundle = ourClient.search().forResource(Person.class).cacheControl(new CacheControlDirective().setNoCache(true)).returnBundle(Bundle.class).execute();
    return BundleUtil.toListOfResourcesOfType(ourCtx, bundle, Person.class);
  }

  @Test
    public void testWebsocketSubscription() throws Exception {
        /*
         * Create subscription
         */
        Subscription subscription = new Subscription();
        subscription.setReason("Monitor new neonatal function (note, age will be determined by the monitor)");
        subscription.setStatus(Subscription.SubscriptionStatus.REQUESTED);
        subscription.setCriteria("Observation?status=final");

	private Patient getGoldenResourcePatient() {
		Bundle bundle = ourClient.search().forResource(Patient.class)
			.withTag("http://hapifhir.io/fhir/NamingSystem/mdm-record-status", "GOLDEN_RECORD")
			.cacheControl(new CacheControlDirective().setNoCache(true)).returnBundle(Bundle.class).execute();
		if (bundle.getEntryFirstRep() != null) {
			return (Patient) bundle.getEntryFirstRep().getResource();
		} else {
			return null;
		}
	}

	@Test
	public void testBatchPutWithIdenticalTags() {
		String batchPuts = "{\n" +
			"\t\"resourceType\": \"Bundle\",\n" +
			"\t\"id\": \"patients\",\n" +
			"\t\"type\": \"batch\",\n" +
			"\t\"entry\": [\n" +
			"\t\t{\n" +
			"\t\t\t\"request\": {\n" +
			"\t\t\t\t\"method\": \"PUT\",\n" +
			"\t\t\t\t\"url\": \"Patient/pat-1\"\n" +
			"\t\t\t},\n" +
			"\t\t\t\"resource\": {\n" +
			"\t\t\t\t\"resourceType\": \"Patient\",\n" +
			"\t\t\t\t\"id\": \"pat-1\",\n" +
			"\t\t\t\t\"meta\": {\n" +
			"\t\t\t\t\t\"tag\": [\n" +
			"\t\t\t\t\t\t{\n" +
			"\t\t\t\t\t\t\t\"system\": \"http://mysystem.org\",\n" +
			"\t\t\t\t\t\t\t\"code\": \"value2\"\n" +
			"\t\t\t\t\t\t}\n" +
			"\t\t\t\t\t]\n" +
			"\t\t\t\t}\n" +
			"\t\t\t},\n" +
			"\t\t\t\"fullUrl\": \"/Patient/pat-1\"\n" +
			"\t\t},\n" +
			"\t\t{\n" +
			"\t\t\t\"request\": {\n" +
			"\t\t\t\t\"method\": \"PUT\",\n" +
			"\t\t\t\t\"url\": \"Patient/pat-2\"\n" +
			"\t\t\t},\n" +
			"\t\t\t\"resource\": {\n" +
			"\t\t\t\t\"resourceType\": \"Patient\",\n" +
			"\t\t\t\t\"id\": \"pat-2\",\n" +
			"\t\t\t\t\"meta\": {\n" +
			"\t\t\t\t\t\"tag\": [\n" +
			"\t\t\t\t\t\t{\n" +
			"\t\t\t\t\t\t\t\"system\": \"http://mysystem.org\",\n" +
			"\t\t\t\t\t\t\t\"code\": \"value2\"\n" +
			"\t\t\t\t\t\t}\n" +
			"\t\t\t\t\t]\n" +
			"\t\t\t\t}\n" +
			"\t\t\t},\n" +
			"\t\t\t\"fullUrl\": \"/Patient/pat-2\"\n" +
			"\t\t}\n" +
			"\t]\n" +
			"}";
		Bundle bundle = FhirContext.forR4().newJsonParser().parseResource(Bundle.class, batchPuts);
		ourClient.transaction().withBundle(bundle).execute();
	}

        // Wait for the subscription to be activated
        await().until(() -> activeSubscriptionCount() == 3);

		Subscription.SubscriptionChannelComponent channel = new Subscription.SubscriptionChannelComponent();
		channel.setType(Subscription.SubscriptionChannelType.WEBSOCKET);
		channel.setPayload("application/json");
		subscription.setChannel(channel);

		MethodOutcome methodOutcome = ourClient.create().resource(subscription).execute();
		IIdType mySubscriptionId = methodOutcome.getId();

		// Wait for the subscription to be activated
		await().atMost(1, TimeUnit.MINUTES).until(() -> activeSubscriptionCount() == 3);

		/*
		 * Attach websocket
		 */

		WebSocketClient myWebSocketClient = new WebSocketClient();
		SocketImplementation mySocketImplementation = new SocketImplementation(mySubscriptionId.getIdPart(),
			EncodingEnum.JSON);

		myWebSocketClient.start();
		URI echoUri = new URI("ws://localhost:" + port + "/websocket");
		ClientUpgradeRequest request = new ClientUpgradeRequest();
		ourLog.info("Connecting to : {}", echoUri);
		Future<Session> connection = myWebSocketClient.connect(mySocketImplementation, echoUri, request);
		Session session = connection.get(2, TimeUnit.SECONDS);

		ourLog.info("Connected to WS: {}", session.isOpen());

		/*
		 * Create a matching resource
		 */
		Observation obs = new Observation();
		obs.setStatus(Observation.ObservationStatus.FINAL);
		ourClient.create().resource(obs).execute();

  private int activeSubscriptionCount() {
    return ourClient.search().forResource(Subscription.class).where(Subscription.STATUS.exactly().code("active")).cacheControl(new CacheControlDirective().setNoCache(true)).returnBundle(Bundle.class).execute().getEntry().size();
  }

  @AfterAll
    public static void afterClass() throws Exception {
        ourServer.stop();
    }

    @BeforeAll
    public static void beforeClass() throws Exception {
        String path = Paths.get("").toAbsolutePath().toString();

	private int activeSubscriptionCount() {
		return ourClient.search().forResource(Subscription.class).where(Subscription.STATUS.exactly().code("active"))
			.cacheControl(new CacheControlDirective().setNoCache(true)).returnBundle(Bundle.class).execute().getEntry()
			.size();
	}

	@BeforeEach
	void beforeEach() {

		ourCtx = FhirContext.forR4();
		ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		String ourServerBase = "http://localhost:" + port + "/fhir/";
		ourClient = ourCtx.newRestfulGenericClient(ourServerBase);

		await().atMost(2, TimeUnit.MINUTES).until(() -> {
			sleep(1000); // execute below function every 1 second
			return activeSubscriptionCount() == 2; // 2 subscription based on mdm-rules.json
		});
	}
}
