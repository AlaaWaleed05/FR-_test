package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The security HEADERS on the operator image route, which no other test of it can see.
 *
 * <p>Every other test in {@link OperatorImageIntegrationTest} runs with
 * {@code @AutoConfigureMockMvc(addFilters = false)} so it can inject an operator identity directly,
 * which also means Spring Security's filter chain — and therefore every header that chain writes —
 * never runs. This class exists solely to run WITH the filters and assert what they emit.
 *
 * <p><strong>The defect that made it necessary (found at review, S8-24).</strong> Spring Security
 * applies {@code headers(withDefaults())} to every chain, and {@code FrameOptionsConfig} defaults
 * to {@code X-Frame-Options: DENY}, which blocks framing even by a same-origin page. BL-136's PDF
 * salary certificate opens in a modal {@code <iframe>} pointed at this route, so under the default
 * the operator would have got the browser's "Refused to display" message instead of the document —
 * the entire deliverable, broken, with a green backend suite and a green backoffice suite either
 * side of it. jsdom never fetches an iframe's {@code src}, so no front-end test could see it
 * either.
 *
 * <p>Asserted against an anonymous request on purpose. The header writer runs before
 * authentication's outcome matters, so a 401 carries the same headers a 200 does, and asserting
 * them here needs no seeded operator, no profile and no artifact. What this cannot show is the
 * body; that is {@link OperatorImageIntegrationTest}'s job, and the two together cover the
 * response.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class OperatorImageHeadersIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void theImageRouteMayBeFramedBySameOriginSoThePdfModalWorks() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(
                    "/api/v1/operator/profiles/"
                        + UUID.randomUUID()
                        + "/artifacts/"
                        + UUID.randomUUID()))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
            .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'self'"))
            .andReturn();

    // Stated as its own assertion because DENY is the value that ships if the headers() block is
    // ever removed, and it is the exact value that breaks the modal.
    assertNotEquals("DENY", result.getResponse().getHeader("X-Frame-Options"));
  }

  /**
   * The relaxation is scoped to chain 1 and must not be read as "framing is fine everywhere". A
   * customer-facing route is on chain 2, which was not touched and keeps the DENY default.
   */
  @Test
  void theCustomerFacingChainStillDeniesFramingEntirely() throws Exception {
    mockMvc
        .perform(get("/api/v1/reference/manifest"))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }
}
