package ca.uhn.fhir.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ca.uhn.fhir.context.ConfigurationException;

class AppPropertiesOauthIssuerTest {

	private static final String ISSUER = "https://auth.example.com/auth/realms/product";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(AppProperties.class);

	@Test
	void enabledWithoutIssuerFailsStartup() {
		runner.withPropertyValues("hapi.fhir.oauth.enabled=true").run(context -> assertIssuerFailure(context.getStartupFailure()));
	}

	@Test
	void enabledWithUnresolvedIssuerPlaceholderFailsStartup() {
		runner.withPropertyValues("hapi.fhir.oauth.enabled=true", "hapi.fhir.oauth.issuer=${OAUTH_ISSUER_UNSET_IN_TEST:}")
				.run(context -> assertIssuerFailure(context.getStartupFailure()));
	}

	@Test
	void enabledWithIssuerStarts() {
		runner.withPropertyValues("hapi.fhir.oauth.enabled=true", "hapi.fhir.oauth.issuer=" + ISSUER).run(context -> {
			assertNull(context.getStartupFailure());
			assertEquals(ISSUER, context.getBean(AppProperties.class).getOauth().getIssuer());
		});
	}

	@Test
	void disabledWithoutIssuerStarts() {
		runner.withPropertyValues("hapi.fhir.oauth.enabled=false").run(context -> assertNull(context.getStartupFailure()));
	}

	private static void assertIssuerFailure(Throwable failure) {
		assertNotNull(failure);
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		assertInstanceOf(ConfigurationException.class, root);
		assertTrue(root.getMessage().contains("OAUTH_ISSUER"), root.getMessage());
	}
}
