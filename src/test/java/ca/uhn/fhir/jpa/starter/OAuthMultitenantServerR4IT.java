package ca.uhn.fhir.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.UrlTenantSelectionInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
  "spring.datasource.url=jdbc:h2:mem:dbr4-oauth-mt",
  "hapi.fhir.fhir_version=r4",
  "hapi.fhir.cr_enabled=false",
  "hapi.fhir.partitioning.partitioning_include_in_search_hashes=false",
  "hapi.fhir.oauth.enabled=true",
  "hapi.fhir.oauth.client_id=client-a",
  "hapi.fhir.oauth.user_role=user-role",
  "hapi.fhir.oauth.admin_role=admin-role"
})
class OAuthMultitenantServerR4IT {

  private static final String KEY_ID = "test-kid";
  private static final String CLIENT_ID = "client-a";
  private static final String TENANT_A = "TENANT-A";
  private static final String TENANT_B = "TENANT-B";
  private static final String REQUEST_ID = "req-abc-123";
  private static final String CORRELATION_ID = "corr-xyz-789";
  private static final String EXTRA_SECURITY_ROLE_TYPE_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/extra-security-role-type";
  private static final String HUMAN_USER_CODE = "humanuser";
  private static final String AUDIT_ENTITY_TYPE_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/audit-entity-type";
  private static final String OBJECT_ROLE_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/object-role";
  private static final String PROVIDER_ID_SYSTEM = "https://elimu.io/systems/providers";
  private static final String PATIENT_ID_SYSTEM = "https://elimu.io/systems/patients";

  private static HttpServer ourJwksServer;
  private static RSAPublicKey ourPublicKey;
  private static RSAPrivateKey ourPrivateKey;
  private static String ourJwksUrl;
  private static UrlTenantSelectionInterceptor ourTenantInterceptor;

  private IGenericClient ourClient;
  private String myAdminToken;
  private String myUserToken;

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    ensureJwksServer();
    registry.add("hapi.fhir.oauth.jwks_url", () -> ourJwksUrl);
  }

  @BeforeEach
  void beforeEach() {
    ourTenantInterceptor = new UrlTenantSelectionInterceptor();
    FhirContext ctx = FhirContext.forR4();
    ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ctx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
    String serverBase = "http://localhost:" + port + "/fhir/";
    ourClient = ctx.newRestfulGenericClient(serverBase);
    ourClient.registerInterceptor(ourTenantInterceptor);

    myAdminToken = tokenForRoles(List.of("admin-role"));
    myUserToken = tokenForRoles(List.of("user-role"));
    ensureTenantExists(1, TENANT_A, myAdminToken);
    ensureTenantExists(2, TENANT_B, myAdminToken);
  }

  @AfterAll
  static void tearDownJwksServer() {
    if (ourJwksServer != null) {
      ourJwksServer.stop(0);
    }
  }

  @Test
  void rosterLaunch_postAllowed_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    int beforeCount = searchAuditEventCount(myAdminToken);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    assertTrue(created.hasIdElement());
    assertEquals(beforeCount + 1, searchAuditEventCount(myAdminToken));
  }

  @Test
  void rosterLaunch_postAllowed_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    int beforeCount = searchAuditEventCount(myAdminToken);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myAdminToken);
    assertTrue(created.hasIdElement());
    assertEquals(beforeCount + 1, searchAuditEventCount(myAdminToken));
  }

  @Test
  void rosterLaunch_searchDenied_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);

    assertForbidden(() -> searchAuditEventCount(myUserToken));
  }

  @Test
  void rosterLaunch_searchAllowed_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    int beforeCount = searchAuditEventCount(myAdminToken);
    createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);

    assertEquals(beforeCount + 1, searchAuditEventCount(myAdminToken));
  }

  @Test
  void auditEvent_historyDenied_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);

    assertForbidden(() -> historyAuditEventCount(myUserToken));
  }

  @Test
  void auditEvent_historyAllowed_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);

    assertTrue(historyAuditEventCount(myAdminToken) > 0);
  }

  @Test
  void auditEvent_readDenied_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    String auditEventId = created.getIdElement().toUnqualifiedVersionless().getValue();

    assertForbidden(() -> readAuditEvent(auditEventId, myUserToken));
  }

  @Test
  void auditEvent_readAllowed_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);

    AuditEvent persisted = readAuditEvent(created.getIdElement().toUnqualifiedVersionless().getValue(), myAdminToken);
    assertEquals(created.getIdElement().toUnqualifiedVersionless().getValue(), persisted.getIdElement().toUnqualifiedVersionless().getValue());
  }

  @Test
  void auditEvent_putDenied_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    String auditEventId = created.getIdElement().toUnqualifiedVersionless().getValue();
    AuditEvent replacement = buildRosterLaunchAuditEvent();
    replacement.setId(auditEventId);

    assertForbidden(() -> updateAuditEvent(auditEventId, replacement, myUserToken));
  }

  @Test
  void auditEvent_putDenied_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    String auditEventId = created.getIdElement().toUnqualifiedVersionless().getValue();
    AuditEvent replacement = buildRosterLaunchAuditEvent();
    replacement.setId(auditEventId);

    assertForbidden(() -> updateAuditEvent(auditEventId, replacement, myAdminToken));
  }

  @Test
  void auditEvent_deleteDenied_forNonAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    String auditEventId = created.getIdElement().toUnqualifiedVersionless().getValue();

    assertForbidden(() -> deleteAuditEvent(auditEventId, myUserToken));
  }

  @Test
  void auditEvent_deleteDenied_forAdminRole() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    String auditEventId = created.getIdElement().toUnqualifiedVersionless().getValue();

    assertForbidden(() -> deleteAuditEvent(auditEventId, myAdminToken));
  }

  @Test
  void rosterLaunch_tenantIsolation_enforcedForSearch() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    int tenantACount = searchAuditEventCount(myAdminToken);

    ourTenantInterceptor.setTenantId(TENANT_B);
    int tenantBCount = searchAuditEventCount(myAdminToken);

    assertTrue(tenantACount > 0);
    assertEquals(0, tenantBCount);
  }

  @Test
  void rosterLaunch_omitsEntityWhat() {
    AuditEvent persisted = createAndReadRosterEvent();
    assertFalse(persisted.getEntityFirstRep().hasWhat(), "Roster launch must omit entity.what patient reference");
  }

  @Test
  void rosterLaunch_setsLaunchModeRoster() {
    AuditEvent persisted = createAndReadRosterEvent();
    assertEquals("roster", launchModeValue(persisted));
  }

  @Test
  void rosterLaunch_omitsEntityQuery() {
    AuditEvent persisted = createAndReadRosterEvent();
    assertFalse(persisted.getEntityFirstRep().hasQuery(), "Payload should not contain query blobs");
  }

  @Test
  void facetLaunch_setsEntityWhatPatientReference() {
    AuditEvent persisted = createAndReadFacetEvent("patient-456");
    assertEquals(PATIENT_ID_SYSTEM, persisted.getEntityFirstRep().getWhat().getIdentifier().getSystem());
    assertEquals("patient-456", persisted.getEntityFirstRep().getWhat().getIdentifier().getValue());
  }

  @Test
  void facetLaunch_requestorTypeCoding_matchesGuide() {
    AuditEvent persisted = createAndReadFacetEvent("patient-456");
    Coding requestorTypeCoding = requestorAgent(persisted).getType().getCodingFirstRep();
    assertEquals(EXTRA_SECURITY_ROLE_TYPE_SYSTEM, requestorTypeCoding.getSystem());
    assertEquals(HUMAN_USER_CODE, requestorTypeCoding.getCode());
  }

  @Test
  void facetLaunch_setsLaunchModeFacet() {
    AuditEvent persisted = createAndReadFacetEvent("patient-456");
    assertEquals("facet", launchModeValue(persisted));
  }

  @Test
  void facetLaunch_omitsEntityWhatDisplay() {
    AuditEvent persisted = createAndReadFacetEvent("patient-456");
    assertFalse(persisted.getEntityFirstRep().getWhat().hasDisplay(), "entity.what.display should be omitted");
  }

  @Test
  void engagePatientLaunch_setsPatientAgentWhoReference() {
    AuditEvent persisted = createAndReadEngageEvent("patient-456");
    assertEquals(PATIENT_ID_SYSTEM, requestorAgent(persisted).getWho().getIdentifier().getSystem());
    assertEquals("patient-456", requestorAgent(persisted).getWho().getIdentifier().getValue());
  }

  @Test
  void engagePatientLaunch_setsEntityWhatPatientReference() {
    AuditEvent persisted = createAndReadEngageEvent("patient-456");
    assertEquals(PATIENT_ID_SYSTEM, persisted.getEntityFirstRep().getWhat().getIdentifier().getSystem());
    assertEquals("patient-456", persisted.getEntityFirstRep().getWhat().getIdentifier().getValue());
  }

  @Test
  void engagePatientLaunch_omitsLaunchModeDetail() {
    AuditEvent persisted = createAndReadEngageEvent("patient-456");
    assertFalse(hasLaunchModeDetail(persisted), "Engage patient launch should not include launch-mode");
  }

  private void ensureTenantExists(int tenantId, String tenantName, String adminToken) {
    ourTenantInterceptor.setTenantId("DEFAULT");
    try {
      ourClient
        .operation()
        .onServer()
        .named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
        .withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID, new IntegerType(tenantId))
        .andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType(tenantName))
        .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(adminToken))
        .execute();
    } catch (BaseServerResponseException e) {
      if (isAlreadyExistsPartitionError(e)) {
        return;
      }
      throw e;
    }
  }

  private boolean isAlreadyExistsPartitionError(BaseServerResponseException e) {
    if (e.getStatusCode() == 409) {
      return true;
    }
    String message = e.getMessage();
    return e.getStatusCode() == 400
      && message != null
      && (message.contains("HAPI-1309")
      || message.contains("already defined")
      || message.contains("already exists"));
  }

  private AuditEvent createAuditEvent(AuditEvent event, String token) {
    return (AuditEvent) ourClient
      .create()
      .resource(event)
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute()
      .getResource();
  }

  private AuditEvent readAuditEvent(String id, String token) {
    return ourClient
      .read()
      .resource(AuditEvent.class)
      .withId(id)
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute();
  }

  private void updateAuditEvent(String id, AuditEvent replacement, String token) {
    ourClient
      .update()
      .resource(replacement)
      .withId(id)
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute();
  }

  private void deleteAuditEvent(String id, String token) {
    ourClient
      .delete()
      .resourceById(new IdType(id))
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute();
  }

  private void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
    BaseServerResponseException failure = assertThrows(BaseServerResponseException.class, executable);
    assertEquals(403, failure.getStatusCode());
  }

  private int searchAuditEventCount(String token) {
    Bundle result = ourClient
      .search()
      .forResource(AuditEvent.class)
      .returnBundle(Bundle.class)
      .cacheControl(new CacheControlDirective().setNoCache(true))
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute();
    return result.getEntry().size();
  }

  private int historyAuditEventCount(String token) {
    Bundle result = ourClient
      .history()
      .onType(AuditEvent.class)
      .returnBundle(Bundle.class)
      .cacheControl(new CacheControlDirective().setNoCache(true))
      .withAdditionalHeader(HttpHeaders.AUTHORIZATION, bearer(token))
      .execute();
    return result.getEntry().size();
  }

  private AuditEvent createAndReadRosterEvent() {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildRosterLaunchAuditEvent(), myUserToken);
    return readAuditEvent(created.getIdElement().toUnqualifiedVersionless().getValue(), myAdminToken);
  }

  private AuditEvent createAndReadFacetEvent(String patientId) {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildFacetLaunchAuditEvent(patientId), myUserToken);
    return readAuditEvent(created.getIdElement().toUnqualifiedVersionless().getValue(), myAdminToken);
  }

  private AuditEvent createAndReadEngageEvent(String patientId) {
    ourTenantInterceptor.setTenantId(TENANT_A);
    AuditEvent created = createAuditEvent(buildEngagePatientLaunchAuditEvent(patientId), myUserToken);
    return readAuditEvent(created.getIdElement().toUnqualifiedVersionless().getValue(), myAdminToken);
  }

  private AuditEvent buildRosterLaunchAuditEvent() {
    AuditEvent event = buildBaseAuditEvent("hypertension-web");
    Reference providerRef = new Reference();
    providerRef.setIdentifier(new Identifier().setSystem(PROVIDER_ID_SYSTEM).setValue("provider-123"));
    event.addAgent()
      .setRequestor(true)
      .setType(new CodeableConcept().addCoding(new Coding(EXTRA_SECURITY_ROLE_TYPE_SYSTEM, HUMAN_USER_CODE, null)))
      .setWho(providerRef);
    AuditEvent.AuditEventEntityComponent entity = event.addEntity();
    entity.setType(new Coding(AUDIT_ENTITY_TYPE_SYSTEM, "2", "System Object"));
    entity.addDetail().setType("launch-mode").setValue(new StringType("roster"));
    entity.addDetail().setType("X-Request-Id").setValue(new StringType(REQUEST_ID));
    entity.addDetail().setType("X-Correlation-Id").setValue(new StringType(CORRELATION_ID));
    return event;
  }

  private AuditEvent buildFacetLaunchAuditEvent(String patientId) {
    AuditEvent event = buildBaseAuditEvent("hypertension-web");
    Reference providerRef = new Reference();
    providerRef.setIdentifier(new Identifier().setSystem(PROVIDER_ID_SYSTEM).setValue("provider-123"));
    event.addAgent()
      .setRequestor(true)
      .setType(new CodeableConcept().addCoding(new Coding(EXTRA_SECURITY_ROLE_TYPE_SYSTEM, HUMAN_USER_CODE, null)))
      .setWho(providerRef);
    AuditEvent.AuditEventEntityComponent entity = event.addEntity();
    Reference patientRef = new Reference();
    patientRef.setIdentifier(new Identifier().setSystem(PATIENT_ID_SYSTEM).setValue(patientId));
    entity.setWhat(patientRef);
    entity.setType(new Coding(AUDIT_ENTITY_TYPE_SYSTEM, "1", "Person"));
    entity.setRole(new Coding(OBJECT_ROLE_SYSTEM, "1", "Patient"));
    entity.addDetail().setType("launch-mode").setValue(new StringType("facet"));
    entity.addDetail().setType("X-Request-Id").setValue(new StringType(REQUEST_ID));
    entity.addDetail().setType("X-Correlation-Id").setValue(new StringType(CORRELATION_ID));
    return event;
  }

  private AuditEvent buildEngagePatientLaunchAuditEvent(String patientId) {
    AuditEvent event = buildBaseAuditEvent("engage-web");
    Reference patientRef = new Reference();
    patientRef.setIdentifier(new Identifier().setSystem(PATIENT_ID_SYSTEM).setValue(patientId));
    event.addAgent()
      .setRequestor(true)
      .setType(new CodeableConcept().addCoding(new Coding(EXTRA_SECURITY_ROLE_TYPE_SYSTEM, HUMAN_USER_CODE, null)))
      .setWho(patientRef.copy());
    AuditEvent.AuditEventEntityComponent entity = event.addEntity();
    entity.setWhat(patientRef.copy());
    entity.setType(new Coding(AUDIT_ENTITY_TYPE_SYSTEM, "1", "Person"));
    entity.setRole(new Coding(OBJECT_ROLE_SYSTEM, "1", "Patient"));
    entity.addDetail().setType("X-Request-Id").setValue(new StringType(REQUEST_ID));
    entity.addDetail().setType("X-Correlation-Id").setValue(new StringType(CORRELATION_ID));
    return event;
  }

  private AuditEvent buildBaseAuditEvent(String appId) {
    AuditEvent event = new AuditEvent();
    event.setType(new Coding("http://dicom.nema.org/resources/ontology/DCM", "110100", "Application Activity"));
    event.addSubtype(new Coding("http://dicom.nema.org/resources/ontology/DCM", "110120", "Application Start"));
    event.setAction(AuditEvent.AuditEventAction.E);
    event.setRecorded(Date.from(Instant.now()));
    event.setOutcome(AuditEvent.AuditEventOutcome._0);

    Reference appRef = new Reference();
    appRef.setIdentifier(new Identifier()
      .setSystem("https://elimu.io/systems/apps")
      .setValue(appId));
    appRef.setDisplay(appDisplayFor(appId));
    event.addAgent()
      .setRequestor(false)
      .setType(new CodeableConcept().addCoding(new Coding(
        "http://dicom.nema.org/resources/ontology/DCM", "110150", "Application")))
      .setAltId(appAltIdFor(appId))
      .setWho(appRef);

    Reference observerRef = new Reference();
    observerRef.setIdentifier(new Identifier()
      .setSystem("https://elimu.io/systems/services")
      .setValue("audit-services"));
    observerRef.setDisplay("Sapphire Audit Services");
    event.setSource(new AuditEvent.AuditEventSourceComponent().setObserver(observerRef));

    return event;
  }

  private boolean hasLaunchModeDetail(AuditEvent event) {
    for (AuditEvent.AuditEventEntityDetailComponent detail : event.getEntityFirstRep().getDetail()) {
      if ("launch-mode".equals(detail.getType())) {
        return true;
      }
    }
    return false;
  }

  private String launchModeValue(AuditEvent event) {
    for (AuditEvent.AuditEventEntityDetailComponent detail : event.getEntityFirstRep().getDetail()) {
      if ("launch-mode".equals(detail.getType())) {
        Type value = detail.getValue();
        if (value instanceof StringType stringType) {
          return stringType.getValue();
        }
      }
    }
    return null;
  }

  private AuditEvent.AuditEventAgentComponent requestorAgent(AuditEvent event) {
    for (AuditEvent.AuditEventAgentComponent agent : event.getAgent()) {
      if (agent.getRequestor()) {
        return agent;
      }
    }
    throw new IllegalStateException("No requestor agent present");
  }

  private static String tokenForRoles(List<String> roles) {
    Algorithm algorithm = Algorithm.RSA256(ourPublicKey, ourPrivateKey);
    Map<String, Object> resourceAccess = Map.of(
      CLIENT_ID, Map.of("roles", roles)
    );
    return JWT.create()
      .withKeyId(KEY_ID)
      .withIssuer("http://test-issuer")
      .withSubject("test-subject")
      .withIssuedAt(Date.from(Instant.now()))
      .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
      .withClaim("resource_access", resourceAccess)
      .sign(algorithm);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private String appDisplayFor(String appId) {
    if ("engage-web".equals(appId)) {
      return "Sapphire Engage";
    }
    return "Sapphire Hypertension Facet";
  }

  private String appAltIdFor(String appId) {
    if ("engage-web".equals(appId)) {
      return "client-id-engage-123";
    }
    return "client-id-xyz-123";
  }

  private static synchronized void ensureJwksServer() {
    if (ourJwksServer != null) {
      return;
    }
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      ourPublicKey = (RSAPublicKey) keyPair.getPublic();
      ourPrivateKey = (RSAPrivateKey) keyPair.getPrivate();

      ourJwksServer = HttpServer.create(new InetSocketAddress(0), 0);
      ourJwksServer.createContext("/jwks", exchange -> {
        byte[] response = buildJwksJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
          os.write(response);
        }
      });
      ourJwksServer.start();
      ourJwksUrl = "http://localhost:" + ourJwksServer.getAddress().getPort() + "/jwks";
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new RuntimeException("Failed to start local JWKS server", e);
    }
  }

  private static String buildJwksJson() {
    String e = base64Url(ourPublicKey.getPublicExponent());
    String n = base64Url(ourPublicKey.getModulus());
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KEY_ID + "\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
  }

  private static String base64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      bytes = trimmed;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
