package ca.uhn.fhir.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.ConfigurationException;

class TenantLoggingPropertiesTest {

	@Test
	void unset_isValidAndEmpty() {
		AppProperties properties = new AppProperties();

		assertDoesNotThrow(properties::validate);
		assertTrue(properties.getTenant_logging().parsePartitionTenants().isEmpty());
	}

	@Test
	void partitionTenants_parsesEntriesAndSkipsBlanks() {
		AppProperties properties = new AppProperties();
		properties.getTenant_logging().setPartition_tenants(List.of(" sapphire = elimu_demo", "", "e2e-ehr=elimu_e2e"));

		assertDoesNotThrow(properties::validate);
		assertEquals(Map.of("sapphire", "elimu_demo", "e2e-ehr", "elimu_e2e"),
			properties.getTenant_logging().parsePartitionTenants());
	}

	@Test
	void tenantAndPartitionTenantsBothSet_isRefused() {
		AppProperties properties = new AppProperties();
		properties.getTenant_logging().setTenant("baystate");
		properties.getTenant_logging().setPartition_tenants(List.of("sapphire=elimu_demo"));

		assertThrows(ConfigurationException.class, properties::validate);
	}

	@Test
	void malformedPartitionTenant_isRefused() {
		for (String entry : List.of("sapphire", "sapphire=", "=elimu_demo")) {
			AppProperties properties = new AppProperties();
			properties.getTenant_logging().setPartition_tenants(List.of(entry));

			assertThrows(ConfigurationException.class, properties::validate, entry);
		}
	}

	@Test
	void duplicatePartition_isRefused() {
		AppProperties properties = new AppProperties();
		properties.getTenant_logging().setPartition_tenants(List.of("sapphire=elimu_demo", "sapphire=elimu_e2e"));

		assertThrows(ConfigurationException.class, properties::validate);
	}
}
