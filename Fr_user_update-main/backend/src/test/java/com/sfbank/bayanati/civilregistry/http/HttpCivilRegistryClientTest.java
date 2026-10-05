package com.sfbank.bayanati.civilregistry.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The adapter against every response shape observed live on 2026-09-04 or decided at AD-002b, plus
 * the malformed shapes the coarse classification must route to {@code not_found}. {@code
 * MockRestServiceServer} replaces the request factory, so nothing here proves the timeouts — {@link
 * HttpCivilRegistryClientTimeoutTest} does.
 *
 * <p>Fabricated values only (CLAUDE.md hard rule): the identity numbers are short zero/one runs
 * that match no real record shape, and every field value is invented.
 */
class HttpCivilRegistryClientTest {

  private static final String ENDPOINT = "https://registry.invalid:5353/CRSAPI/Services/GetCRSData";
  private static final String NID = "00000000001";
  private static final byte[] PHOTO_BYTES = "not-a-real-jpeg".getBytes(StandardCharsets.UTF_8);
  private static final String PHOTO_BASE64 = Base64.getEncoder().encodeToString(PHOTO_BYTES);

  private MockRestServiceServer server;
  private HttpCivilRegistryClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client =
        new HttpCivilRegistryClient(
            builder.build(),
            new CivilRegistryHttpProperties(
                URI.create(ENDPOINT), Duration.ofSeconds(5), Duration.ofSeconds(15)));
  }

  /**
   * S7-12, found live on the first real passport ever scanned: a passport prints the national
   * number grouped, Uqudo transcribes it faithfully, and the adapter forwarded the hyphens to a
   * service that matches {@code NID} on bare digits — so every real passport lookup came back as
   * the registry's 400 not-found page and the customer was told to "try again shortly" forever.
   *
   * <p>The assertion is on the REQUEST BYTES, because that is where the defect lived. A test that
   * only checked the outcome would pass against a build that sent the hyphens and happened to get a
   * matching record back from a mock.
   */
  @Test
  void aGroupedNationalNumberIsSentToTheRegistryAsBareDigits() {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"NID\":\"" + NID + "\"}", true))
        .andRespond(withSuccess(fullRecord("\"" + NID + "\""), MediaType.APPLICATION_JSON));

    client.lookup("000-0000-0001");

    server.verify();
  }

  /**
   * The other half of the same fix, and the reason it could not be a one-liner. Once the request
   * carries bare digits the registry answers with bare digits, while the scanned value still holds
   * the document's hyphens — so an identity comparison on the raw strings would fire {@code
   * IDENTITY_NUMBER_MISMATCH} (BL-030, "the failure mode once judged the worst in the system") on
   * every successful passport lookup. Fixing the request alone would have replaced a not-found with
   * a mismatch.
   */
  @Test
  void aGroupedSubmittedNumberStillMatchesTheRegistrysBareAnswer() {
    respondWith(fullRecord("\"" + NID + "\""));

    RegistryLookup lookup = client.lookup("000-0000-0001");

    assertTrue(lookup.found(), "a formatting difference must not read as a different person");
    assertEquals(NID, lookup.identityNumberReturned());
  }

  private void respondWith(String body) {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  /** The fifteen-field shape of the one observed record, every value fabricated. */
  private static String fullRecord(String identityNumberJson) {
    return "{\"IDENTITY_NUMBER\":"
        + identityNumberJson
        + ",\"NAME\":\"محمد\",\"FATHER_NAME\":\"الطيب\",\"GRAND_FATHER_NAME\":\"عبدالله\","
        + "\"GRE_GRA_FATHER_NAME\":\"إبراهيم\",\"MOTHER_NAME\":\"فاطمة\",\"MOT_FATHER_NAME\":\"حسن\","
        + "\"MOT_GRA_FATHER_NAME\":\"عثمان\",\"MOT_GRE_GRA_FATHER_NAME\":\"آدم\","
        + "\"FIRST_NAMES\":\"\",\"LAST_NAME\":\"Altayeb\",\"BIRTH_DATE\":\"01/01/1990\","
        + "\"GENDER\":\"m\",\"ADDRESS\":\"الخرطوم , محلية تجريبية , حي تجريبي , مربع تجريبي\","
        + "\"PHOTOGRAPH\":\""
        + PHOTO_BASE64
        + "\"}";
  }

  @Test
  void theRequestIsAPlainJsonPostOfTheNidAsAStringWithNoAuthentication() {
    // The service binds NID as a string (five authorised tests): leading zeros must survive the
    // wire, so the value is a JSON string, never a number. No auth of any kind is sent.
    server
        .expect(once(), requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"NID\":\"" + NID + "\"}", true))
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
        .andRespond(withSuccess(fullRecord("\"" + NID + "\""), MediaType.APPLICATION_JSON));

    client.lookup(NID);

    server.verify();
  }

  @Test
  void aMatchingPopulatedRecordIsFoundWithEveryFieldParsed() {
    respondWith(fullRecord("\"" + NID + "\""));

    RegistryLookup lookup = client.lookup(NID);

    assertTrue(lookup.found());
    assertEquals(RegistryLookup.MATCHED, lookup.reason());
    assertEquals(NID, lookup.identityNumberReturned());
    RegistryLookupResult record = lookup.record();
    assertEquals(NID, record.identityNumber());
    assertEquals("محمد", record.nameArGiven());
    assertEquals("الطيب", record.nameArFather());
    assertEquals("عبدالله", record.nameArGrandfather());
    assertEquals("إبراهيم", record.nameArGreatGrandfather());
    assertEquals("فاطمة", record.nameArMother());
    assertEquals("حسن", record.nameArMotherFather());
    assertEquals("عثمان", record.nameArMotherGrandfather());
    assertEquals("آدم", record.nameArMotherGreatGrandfather());
    assertNull(record.firstNamesEn(), "an empty FIRST_NAMES (observed) is absence, not a value");
    assertEquals("Altayeb", record.lastNameEn());
    assertEquals(LocalDate.of(1990, 1, 1), record.dateOfBirth());
    assertEquals("m", record.sexRegistry());
    assertEquals("الخرطوم , محلية تجريبية , حي تجريبي , مربع تجريبي", record.rawAddressAr());
    assertArrayEquals(PHOTO_BYTES, record.photograph(), "PHOTOGRAPH decoded once, here");
  }

  @Test
  void theRawExchangeIsByteIdenticalForTheAuditArtifacts() {
    String body = fullRecord("\"" + NID + "\"");
    respondWith(body);

    RegistryLookup lookup = client.lookup(NID);

    assertNotNull(lookup.exchange());
    assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), lookup.exchange().responseBody());
    assertEquals(
        "{\"NID\":\"" + NID + "\"}",
        new String(lookup.exchange().requestBody(), StandardCharsets.UTF_8));
    assertEquals(200, lookup.exchange().httpStatus());
    assertTrue(lookup.exchange().responseMediaType().startsWith("application/json"));
  }

  @Test
  void unparseableBirthDateAndGenderDegradeToNullWithoutRejectingTheRecord() {
    // Q11: safe choice covering the possibilities; the raw values stay in the response artifact.
    respondWith(
        "{\"IDENTITY_NUMBER\":\""
            + NID
            + "\",\"BIRTH_DATE\":\"00/00/0000\",\"GENDER\":\"Male\",\"PHOTOGRAPH\":\""
            + PHOTO_BASE64
            + "\"}");

    RegistryLookup lookup = client.lookup(NID);

    assertTrue(lookup.found());
    assertNull(lookup.record().dateOfBirth());
    assertNull(lookup.record().sexRegistry());
  }

  @Test
  void aMissingPhotographIsStillFoundWithNoPortrait() {
    respondWith("{\"IDENTITY_NUMBER\":\"" + NID + "\",\"NAME\":\"محمد\"}");

    RegistryLookup lookup = client.lookup(NID);

    assertTrue(lookup.found());
    assertNull(lookup.record().photograph());
  }

  @Test
  void anUndecodablePhotographIsStillFoundWithNoPortraitNotA500() {
    // The consumer used to decode unguarded; a corrupt value would have been an
    // IllegalArgumentException out of submitScan after the Uqudo images were already downloaded.
    respondWith("{\"IDENTITY_NUMBER\":\"" + NID + "\",\"PHOTOGRAPH\":\"%%%not base64%%%\"}");

    RegistryLookup lookup = client.lookup(NID);

    assertTrue(lookup.found());
    assertNull(lookup.record().photograph());
  }

  @Test
  void aLineWrappedPhotographStillDecodes() {
    String wrapped = PHOTO_BASE64.substring(0, 8) + "\\r\\n" + PHOTO_BASE64.substring(8);
    respondWith("{\"IDENTITY_NUMBER\":\"" + NID + "\",\"PHOTOGRAPH\":\"" + wrapped + "\"}");

    assertArrayEquals(PHOTO_BYTES, client.lookup(NID).record().photograph());
  }

  @Test
  void theObserved400HtmlPageIsNotFoundAndItsBytesAreKept() {
    // The service's own not-found answer (five authorised tests, 2026-09-04): a GlassFish page.
    String html =
        "<html><body>HTTP Status 400 - Bad Request - The request sent by the client was"
            + " syntactically incorrect.</body></html>";
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML).body(html));

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.NON_SUCCESS_STATUS, lookup.reason());
    assertNull(lookup.identityNumberReturned());
    assertEquals(400, lookup.exchange().httpStatus());
    assertArrayEquals(html.getBytes(StandardCharsets.UTF_8), lookup.exchange().responseBody());
    assertTrue(lookup.exchange().responseMediaType().startsWith("text/html"));
  }

  @Test
  void aNon2xxIsNotFoundEvenWhenItCarriesAMatchingRecord() {
    // AD-002b's rule starts at "a 2xx JSON body": the status gate comes before the parse.
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fullRecord("\"" + NID + "\"")));

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.NON_SUCCESS_STATUS, lookup.reason());
    assertEquals(500, lookup.exchange().httpStatus());
  }

  @Test
  void a204OrAnyEmptyBodyIsNotFound() {
    server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.NO_CONTENT));

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.EMPTY_BODY, lookup.reason());
    assertEquals(0, lookup.exchange().responseBody().length);
  }

  @Test
  void aNonJson2xxIsNotFound() {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(withSuccess("<html>surprise</html>", MediaType.TEXT_HTML));

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.NON_JSON_BODY, lookup.reason());
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "\"00000000001\"", "1", "[{\"IDENTITY_NUMBER\":\"x\"}]"})
  void jsonThatIsNotAnObjectIsNotFound(String body) {
    respondWith(body);

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.NOT_AN_OBJECT, lookup.reason());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"NAME\":\"x\"}", "{\"IDENTITY_NUMBER\":null}"})
  void anObjectWithoutAnIdentityNumberIsNotFound(String body) {
    // The research's candidate-2 not-found shape (200 with an empty record) lands here.
    respondWith(body);

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.IDENTITY_NUMBER_ABSENT, lookup.reason());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"IDENTITY_NUMBER\":1}",
        "{\"IDENTITY_NUMBER\":true}",
        "{\"IDENTITY_NUMBER\":{\"value\":\"00000000001\"}}",
        "{\"IDENTITY_NUMBER\":[\"00000000001\"]}"
      })
  void aNonStringIdentityNumberIsNotFoundNeverCoerced(String body) {
    // A JSON number would already have lost its leading zeros -- 0 and 00000000000 are different
    // records -- so a "repaired" comparison would accept a value the wire has damaged.
    respondWith(body);

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.IDENTITY_NUMBER_NOT_STRING, lookup.reason());
    assertNull(lookup.identityNumberReturned());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"IDENTITY_NUMBER\":\"\"}", "{\"IDENTITY_NUMBER\":\"   \"}"})
  void aBlankIdentityNumberIsNotFound(String body) {
    respondWith(body);

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertEquals(RegistryLookup.IDENTITY_NUMBER_EMPTY, lookup.reason());
  }

  @Test
  void aPopulatedRecordWithADifferentIdentityNumberIsNotFoundAndTheReturnedValueIsKept() {
    // BL-030: the guard's evidence must be auditable, so the differing value comes back; the
    // stranger's record fields do not.
    respondWith(fullRecord("\"00000000002\""));

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found());
    assertNull(lookup.record());
    assertEquals(RegistryLookup.IDENTITY_NUMBER_MISMATCH, lookup.reason());
    assertEquals("00000000002", lookup.identityNumberReturned());
    assertNotNull(lookup.exchange().responseBody(), "the response is still audited in full");
  }

  @ParameterizedTest
  @CsvSource({
    "'1', 'no numeric folding: a leading-zero difference is a different record'",
    "'٠٠٠٠٠٠٠٠٠٠١', 'no Arabic-Indic digit folding'",
    "'00000000001x', 'no prefix matching'"
  })
  void nearMissesThatAreNotWhitespaceAreMismatches(String returned, String why) {
    respondWith("{\"IDENTITY_NUMBER\":\"" + returned + "\"}");

    RegistryLookup lookup = client.lookup(NID);

    assertFalse(lookup.found(), why);
    assertEquals(RegistryLookup.IDENTITY_NUMBER_MISMATCH, lookup.reason(), why);
  }

  @Test
  void leadingAndTrailingWhitespaceOnTheReturnedValueIsTheOneToleratedDifference() {
    // A fixed-width column behind a 2014-era DAO may pad; padding never changes which record
    // matched, and treating it as a mismatch would pause every successful lookup.
    respondWith("{\"IDENTITY_NUMBER\":\"  " + NID + "\\t\"}");

    RegistryLookup lookup = client.lookup(NID);

    assertTrue(lookup.found());
    assertEquals(NID, lookup.record().identityNumber(), "stored stripped");
  }

  @Test
  void noResponseAtAllIsUnreachableWithTheRequestBytesButNoResponseBytes() {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(withException(new IOException("connection reset")));

    RegistryUnreachableException thrown =
        assertThrows(RegistryUnreachableException.class, () -> client.lookup(NID));

    assertNotNull(thrown.getCause());
    assertNotNull(thrown.exchange(), "what was sent is still worth auditing");
    assertEquals(
        "{\"NID\":\"" + NID + "\"}",
        new String(thrown.exchange().requestBody(), StandardCharsets.UTF_8));
    assertNull(thrown.exchange().responseBody());
    assertEquals(0, thrown.exchange().httpStatus());
  }

  @Test
  void theWhitespaceDiagnosticSeesThroughNoBreakSpacesAndBidiMarksButNotDigits() {
    // Log-only helper: it must never widen the accept rule, and the test above proves it does not
    // -- this only pins what the WARN line reports.
    assertTrue(HttpCivilRegistryClient.differsOnlyByWhitespace(NID, "\u00A0" + NID + "\u200F"));
    assertTrue(HttpCivilRegistryClient.differsOnlyByWhitespace(NID, " " + NID + "\u3000"));
    assertFalse(HttpCivilRegistryClient.differsOnlyByWhitespace(NID, "00000000002"));
  }
}
