package ca.uhn.fhir.jpa.starter.interceptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.core.JsonProcessingException;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.AppProperties.Oauth;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;

public class CustomSearchNarrowingInterceptorTest {

	@InjectMocks
	CustomSearchNarrowingInterceptor searchNarrowingInterceptor;

	@Spy
	AppProperties mockConfig = new AppProperties();

	@Spy
	Oauth mockOAuth = new Oauth();

	private static final String TOKEN_WITH_PATIENT_CLAIM = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6IjM4YTcyY2E1M2MyMjMxZTA1NjJhNGJjYTgxZDA0OWQ1In0.eyJwYXRpZW50IjoiNjc1MDI5In0.ARILmchVkIJBYm1QWnrrcGdYRnjTf-_hl6g8V5u1ZKK00gwC_-OxpB71LrH2yQ2vNVagsZeTn6XO43zuH7f96mjpQY18ZLSj-IKIxow52eLDlPkVl6YjB2ntmXj_dEOCUt_DeXeUil3LXCPW1Y2PA4XSu_wTFlIN75bnD1a68n8ky4iBMtuzhEdSvxoU0DawpmtTSpqIqkDR5Kp_sWkEjpplMNhgv0GpQPlQvdVJL60R7KjGm8pf5NdsZ36NFEthQYAFzVDcC-nq_Rm8EsIYjtjv5c9naWRzC3etDEjEjAjQxoNqIZxCULSoJ16f3MQopoXQO-kA3ejxOqK0fYu_ShKgZjkT6VQ4_d66NxQsImLHe8fdLimgp4LISuv6UQXhBximCPzN2S3_pdPEvZWorIvEojrrJrZIdIxxpyM700IhGALh-GVa_3OSWbMuIrPs3TT9iU8ZhWFWHlcGM95PseJCR-BcrL5xE7kvr2HtjTId-anlzjw3x0qxPbD4bk8w";
	private static final String TOKEN_WITHOUT_PATIENT_CLAIM = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6IjM4YTcyY2E1M2MyMjMxZTA1NjJhNGJjYTgxZDA0OWQ1In0.e30.PN2ej0hqZejJW2yGV8LjaVZwNCrinN9N8D6FhdzBQHPD8h0RwqRVLbqfZHD_p8QrRJYL_sJ2tbqxWIuWDSyVEUQehLC-p6KoyUao1Id-PCuejovASeZGmbX9NkalW6s4JPl2cc7sMyg6mU_aGTiyhaTDWtjULemv8Fm6GPNFIYURibIwUsbWhxq0-544vb9t9eWFP5KLj9aa6D1HUxq12l9Md9zfhm0X8v5EIgFH2S1XY-fkIIEryl8R3kgtVRkmElyN2ZgdtTrt9u7bWX_Zm2gnIgiP1omXGpptCyhimlhJ-wohmw-ULjvpUv4GYxEJn5Olhr0vQPPmapg3pgdNR4AnFWL3YTZVeSwZuef4FxHRzVWv5qXf8Yajai3EanxMASM_xxAsuKsFbJ15ehQPGerKIQJPT7_cMakL4f0ohdYZrxsRspzxAWDYTPfsrFtZVIiTi3JiAB6f82OF4T2krKtJVzyjKFYXYmy3XINg2W6-ldBFQ3Y-6lb3oZjM5Ejd";

	@BeforeEach
	public void before() throws FileNotFoundException {
		MockitoAnnotations.openMocks(this);
	}

	@Test
	void buildAuthorizedListWithPatientClaim() throws JsonProcessingException, IllegalArgumentException,
			IllegalAccessException, NoSuchFieldException, SecurityException {
		RequestDetails mockRequestDetails = mock(RequestDetails.class);
		mockOAuth.setEnabled(true);
		when(mockConfig.getOauth()).thenReturn(mockOAuth);
		when(mockRequestDetails.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TOKEN_WITH_PATIENT_CLAIM);
		AuthorizedList rule = searchNarrowingInterceptor.buildAuthorizedList(mockRequestDetails);
		Field privateField = AuthorizedList.class.getDeclaredField("myAllowedCompartments");
		privateField.setAccessible(true);
		List<String> fieldValue = (List<String>) privateField.get(rule);
		Assertions.assertEquals("Patient/675029", fieldValue.get(0));
	}

	@Test
	void buildAuthorizedListWithoutPatientClaim() throws JsonProcessingException, IllegalArgumentException,
			IllegalAccessException, NoSuchFieldException, SecurityException {
		RequestDetails mockRequestDetails = mock(RequestDetails.class);
		mockOAuth.setEnabled(true);
		when(mockConfig.getOauth()).thenReturn(mockOAuth);
		when(mockRequestDetails.getHeader(HttpHeaders.AUTHORIZATION))
				.thenReturn("Bearer " + TOKEN_WITHOUT_PATIENT_CLAIM);
		AuthorizedList rule = searchNarrowingInterceptor.buildAuthorizedList(mockRequestDetails);
		Assertions.assertTrue(rule instanceof AuthorizedList);
	}
}
