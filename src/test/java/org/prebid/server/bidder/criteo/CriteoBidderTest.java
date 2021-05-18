package org.prebid.server.bidder.criteo;

import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Regs;
import com.iab.openrtb.request.User;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.criteo.model.CriteoRequest;
import org.prebid.server.bidder.criteo.model.CriteoRequestSlot;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtImpCriteo;
import org.prebid.server.proto.openrtb.ext.request.ExtRegs;
import org.prebid.server.util.HttpUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.vertx.core.MultiMap;

import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

public class CriteoBidderTest extends VertxTest {

    private static final String ENDPOINT_URL = "https://test.endpoint.com";

    private CriteoBidder criteoBidder;

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private CriteoSlotIdGenerator criteoSlotIdGenerator;

    @Before
    public void setUp() {
        given(criteoSlotIdGenerator.generateUuid()).willReturn("0000-0000-0000-0000");
        criteoBidder = new CriteoBidder(ENDPOINT_URL, jacksonMapper, criteoSlotIdGenerator);
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new CriteoBidder("invalid_url", jacksonMapper, criteoSlotIdGenerator));
    }

    @Test
    public void makeHttpRequestShouldBuildCorrectCriteoSlot() {
        // given
        final BidRequest bidRequest = givenBidRequest(identity());

        // when
        final Result<List<HttpRequest<CriteoRequest>>> result = criteoBidder.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1)
                .extracting(HttpRequest::getPayload)
                .flatExtracting(CriteoRequest::getSlots)
                .containsExactly(
                        CriteoRequestSlot.builder()
                                .slotId("0000-0000-0000-0000")
                                .impId("imp_id")
                                .zoneId(1)
                                .networkId(1)
                                .sizes(List.of("300x300"))
                                .build()
                );

    }

    @Test
    public void makeHttpRequestShouldThrowErrorIfImpsNetworkIdIsDifferent() {
        // given
        final BidRequest bidRequest =
                BidRequest.builder()
                        .imp(
                                List.of(
                                        Imp.builder()
                                                .id("imp_1_id")
                                                .banner(Banner.builder()
                                                        .id("banner_1_id")
                                                        .h(300)
                                                        .w(300)
                                                        .build()
                                                )
                                                .ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpCriteo.of(1, 1))))
                                                .build(),
                                        Imp.builder()
                                                .id("imp_2_id")
                                                .banner(Banner.builder()
                                                        .id("banner_2_id")
                                                        .h(350)
                                                        .w(350)
                                                        .build()
                                                )
                                                .ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpCriteo.of(1, 2))))
                                                .build()
                                )
                        )
                        .regs(Regs.of(null, ExtRegs.of(1, null)))
                        .build();

        // when
        final Result<List<HttpRequest<CriteoRequest>>> result = criteoBidder.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage())
                .isEqualTo("Bid request has slots coming with several network IDs which is not allowed");
        assertThat(result.getValue()).hasSize(0);
    }

    @Test
    public void makeHttpRequestsShouldSetCookieUidHeaderIfUserIsNotNull() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                requestBuilder -> requestBuilder
                        .user(User.builder().buyeruid("buyerid").build()),
                identity());

        // when
        final Result<List<HttpRequest<CriteoRequest>>> result = criteoBidder.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1)
                .extracting(HttpRequest::getHeaders)
                .flatExtracting(MultiMap::entries)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .contains(
                        tuple(HttpUtil.COOKIE_HEADER.toString(), "uid=buyerid")
                );
    }

    @Test
    public void makeHttpRequestsShouldSetUserAgentAndForwarderForHeadersIfBidRequestDeviceIsNotNull() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                requestBuilder -> requestBuilder
                        .device(Device.builder()
                                .os("ios")
                                .ip("255.255.255.255")
                                .ua("userAgent")
                                .build()),
                identity());

        // when
        final Result<List<HttpRequest<CriteoRequest>>> result = criteoBidder.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1)
                .extracting(HttpRequest::getHeaders)
                .flatExtracting(MultiMap::entries)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .contains(
                        tuple(HttpUtil.USER_AGENT_HEADER.toString(), "userAgent"),
                        tuple(HttpUtil.X_FORWARDED_FOR_HEADER.toString(), "255.255.255.255")
                );
    }

    private static BidRequest givenBidRequest(Function<Imp.ImpBuilder, Imp.ImpBuilder> impCustomizer) {
        return givenBidRequest(identity(), impCustomizer);
    }

    private static BidRequest givenBidRequest(
            Function<BidRequest.BidRequestBuilder, BidRequest.BidRequestBuilder> bidRequestCustomizer,
            Function<Imp.ImpBuilder, Imp.ImpBuilder> impCustomizer) {

        return bidRequestCustomizer.apply(BidRequest.builder()
                .imp(singletonList(givenImp(impCustomizer))))
                .regs(Regs.of(null, ExtRegs.of(1, null)))
                .build();
    }

    private static Imp givenImp(Function<Imp.ImpBuilder, Imp.ImpBuilder> impCustomizer) {
        return impCustomizer.apply(Imp.builder()
                .id("imp_id")
                .banner(Banner.builder()
                        .id("banner_id")
                        .h(300)
                        .w(300)
                        .build()
                )
                .ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpCriteo.of(1, 1)))))
                .build();
    }

}
