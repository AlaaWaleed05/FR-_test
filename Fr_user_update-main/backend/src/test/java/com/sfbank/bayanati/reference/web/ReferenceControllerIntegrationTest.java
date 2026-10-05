package com.sfbank.bayanati.reference.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.reference.jdbc.ReferenceDocumentPublisher;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S4-04's two read endpoints, against the real HTTP stack and a real PostgreSQL 18 with every
 * migration applied — {@code fru_app} (the endpoints' runtime role) now holds {@code SELECT} on
 * {@code ref.reference_list_document} (V0052).
 *
 * <p>Not branch/account-number scoped (per {@code AbstractPostgresIntegrationTest}'s rule) — this
 * class touches no {@code app.profile} row at all, only the shared, idempotently-republishable
 * reference documents, exactly the same safe-to-republish reasoning {@code
 * ReferenceDocumentPublisherIntegrationTest} (S4-03) already documents for itself.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class ReferenceControllerIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void republishOccupation() {
    // Publication is fru_migrator-only (V0048/V0012) -- same pattern
    // ReferenceDocumentPublisherIntegrationTest uses to get a real, byte-identical document row in
    // place before exercising the read side. Idempotent, so re-running this every test is safe.
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD);
    JdbcTemplate migrator = new JdbcTemplate(dataSource);
    ReferenceDocumentPublisher publisher =
        new ReferenceDocumentPublisher(migrator, new DataSourceTransactionManager(dataSource));
    publisher.publish("occupation", 1);
  }

  @Test
  void manifestListsEveryCurrentVersionWithAnEtag() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/reference/manifest"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    String etag = result.getResponse().getHeader("ETag");

    assertTrue(body.contains("\"listCode\":\"occupation\""), body);
    assertTrue(body.contains("\"documentPath\":\"/api/v1/reference/lists/occupation/1\""), body);
    assertTrue(body.contains("\"verifiableChannels\":[\"sms\",\"whatsapp\",\"email\"]"), body);
    assertEquals(66, etag.length(), "expected a quoted 64-hex-char sha256: " + etag); // "..."
  }

  @Test
  void manifestConditionalRequestReturnsNotModifiedWithNoBody() throws Exception {
    String etag =
        mockMvc
            .perform(get("/api/v1/reference/manifest"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(get("/api/v1/reference/manifest").header("If-None-Match", etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string("ETag", etag))
        .andExpect(result -> assertEquals(0, result.getResponse().getContentLength()));
  }

  @Test
  void listEndpointBytesHashToTheStoredContentHash() throws Exception {
    byte[] storedContentHash =
        jdbcTemplate.queryForObject(
            "SELECT content_hash FROM ref.reference_list_version"
                + " WHERE list_code = 'occupation' AND version = 1",
            byte[].class);
    String expectedHex = HexFormat.of().formatHex(storedContentHash);

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/reference/lists/occupation/1"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
            .andExpect(header().string("ETag", "\"" + expectedHex + "\""))
            .andReturn();

    byte[] body = result.getResponse().getContentAsByteArray();
    String actualHex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

    assertEquals(expectedHex, actualHex, "served bytes must hash to exactly content_hash");
  }

  @Test
  void listEndpointConditionalRequestReturnsNotModifiedWithNoBody() throws Exception {
    String etag =
        mockMvc
            .perform(get("/api/v1/reference/lists/occupation/1"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(get("/api/v1/reference/lists/occupation/1").header("If-None-Match", etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string("ETag", etag))
        .andExpect(result -> assertEquals(0, result.getResponse().getContentLength()));
  }

  @Test
  void listEndpointUnpublishedVersionReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/reference/lists/occupation/999")).andExpect(status().isNotFound());
  }
}
