package org.prebid.server.it;

import io.restassured.response.Response;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.prebid.server.model.Endpoint;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Collections.singletonList;

public class LoganTest extends IntegrationTest {

    @Test
    public void openrtb2AuctionShouldRespondWithBidsFromLogan() throws IOException, JSONException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/logan-exchange"))
                .withRequestBody(equalToJson(jsonFrom("openrtb2/logan/test-logan-bid-request.json")))
                .willReturn(aResponse().withBody(jsonFrom("openrtb2/logan/test-logan-bid-response.json"))));

        // when
        final Response response = responseFor("openrtb2/logan/test-auction-logan-request.json",
                Endpoint.openrtb2_auction);

        // then
        assertJsonEquals("openrtb2/logan/test-auction-logan-response.json", response, singletonList("logan"));
    }
}
