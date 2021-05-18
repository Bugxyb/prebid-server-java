package org.prebid.server.auction.requestfactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.ImplicitParametersExtractor;
import org.prebid.server.auction.InterstitialProcessor;
import org.prebid.server.auction.OrtbTypesResolver;
import org.prebid.server.auction.PrivacyEnforcementService;
import org.prebid.server.auction.StoredRequestProcessor;
import org.prebid.server.auction.TimeoutResolver;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.geolocation.model.GeoInfo;
import org.prebid.server.metric.MetricName;
import org.prebid.server.model.HttpRequestWrapper;
import org.prebid.server.privacy.ccpa.Ccpa;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.model.Privacy;
import org.prebid.server.privacy.model.PrivacyContext;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidData;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidDataEidPermissions;
import org.prebid.server.settings.model.Account;

import java.util.ArrayList;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.prebid.server.assertion.FutureAssertion.assertThat;

public class AuctionRequestFactoryTest extends VertxTest {

    private static final String ACCOUNT_ID = "acc_id";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private Ortb2RequestFactory ortb2RequestFactory;
    @Mock
    private StoredRequestProcessor storedRequestProcessor;
    @Mock
    private ImplicitParametersExtractor paramsExtractor;
    @Mock
    private Ortb2ImplicitParametersResolver paramsResolver;
    @Mock
    private InterstitialProcessor interstitialProcessor;
    @Mock
    private OrtbTypesResolver ortbTypesResolver;
    @Mock
    private PrivacyEnforcementService privacyEnforcementService;
    @Mock
    private TimeoutResolver timeoutResolver;

    @Mock
    private AuctionRequestFactory target;

    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerRequest httpRequest;

    private Account defaultAccount;
    private BidRequest defaultBidRequest;
    private AuctionContext defaultActionContext;

    @Before
    public void setUp() {
        defaultBidRequest = BidRequest.builder().build();
        defaultAccount = Account.empty(ACCOUNT_ID);

        final PrivacyContext defaultPrivacyContext = PrivacyContext.of(
                Privacy.of("0", EMPTY, Ccpa.EMPTY, 0),
                TcfContext.empty());
        defaultActionContext = AuctionContext.builder()
                .bidRequest(defaultBidRequest)
                .account(defaultAccount)
                .prebidErrors(new ArrayList<>())
                .privacyContext(defaultPrivacyContext)
                .build();

        given(routingContext.request()).willReturn(httpRequest);
        given(httpRequest.headers()).willReturn(MultiMap.caseInsensitiveMultiMap());
        given(httpRequest.remoteAddress()).willReturn(new SocketAddressImpl(1234, "host"));

        given(timeoutResolver.resolve(any())).willReturn(2000L);
        given(timeoutResolver.adjustTimeout(anyLong())).willReturn(1900L);

        given(ortb2RequestFactory.createAuctionContext(any())).willReturn(defaultActionContext);
        given(ortb2RequestFactory.executeEntrypointHooks(any(), any(), any()))
                .willAnswer(invocation -> toHttpRequest(invocation.getArgument(0), invocation.getArgument(1)));
        given(ortb2RequestFactory.executeRawAuctionRequestHooks(any()))
                .willAnswer(invocation -> Future.succeededFuture(
                        ((AuctionContext) invocation.getArgument(0)).getBidRequest()));

        given(paramsResolver.resolve(any(), any(), any()))
                .will(invocationOnMock -> invocationOnMock.getArgument(0));
        given(ortb2RequestFactory.validateRequest(any()))
                .will(invocationOnMock -> invocationOnMock.getArgument(0));
        given(interstitialProcessor.process(any()))
                .will(invocationOnMock -> invocationOnMock.getArgument(0));

        given(privacyEnforcementService.contextFromBidRequest(any()))
                .willReturn(Future.succeededFuture(defaultPrivacyContext));

        given(ortb2RequestFactory.enrichBidRequestWithAccountAndPrivacyData(any()))
                .willAnswer(invocation -> ((AuctionContext) invocation.getArgument(0)).getBidRequest());
        given(ortb2RequestFactory.executeProcessedAuctionRequestHooks(any()))
                .willAnswer(invocation -> Future.succeededFuture(
                        ((AuctionContext) invocation.getArgument(0)).getBidRequest()));
        given(ortb2RequestFactory.restoreResultFromRejection(any()))
                .willAnswer(invocation -> Future.failedFuture((Throwable) invocation.getArgument(0)));

        target = new AuctionRequestFactory(
                Integer.MAX_VALUE,
                ortb2RequestFactory,
                storedRequestProcessor,
                paramsExtractor,
                paramsResolver,
                interstitialProcessor,
                ortbTypesResolver,
                privacyEnforcementService,
                timeoutResolver,
                jacksonMapper);
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBody()).willReturn(null);

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Incoming request has no body");
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyExceedsMaxRequestSize() {
        // given
        target = new AuctionRequestFactory(
                1,
                ortb2RequestFactory,
                storedRequestProcessor,
                paramsExtractor,
                paramsResolver,
                interstitialProcessor,
                ortbTypesResolver,
                privacyEnforcementService,
                timeoutResolver,
                jacksonMapper);

        given(routingContext.getBodyAsString()).willReturn("body");

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Request size exceeded max size of 1 bytes.");
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBodyAsString()).willReturn("body");

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause()).isInstanceOf(InvalidRequestException.class);
        assertThat(((InvalidRequestException) future.cause()).getMessages()).hasSize(1)
                .element(0).asString().startsWith("Error decoding bidRequest: Unrecognized token 'body'");
    }

    @Test
    public void shouldUseBodyAndHeadersModifiedByEntrypointHooks() {
        // given
        final BidRequest receivedBidRequest = BidRequest.builder()
                .device(Device.builder().dnt(1).build())
                .site(Site.builder().domain("example.com").build())
                .build();

        givenBidRequest(receivedBidRequest);

        final String rawModifiedBidRequest = bidRequestToString(BidRequest.builder()
                .app(App.builder().bundle("org.company.application").build())
                .build());
        doAnswer(invocation -> Future.succeededFuture(HttpRequestWrapper.builder().body(rawModifiedBidRequest).build()))
                .when(ortb2RequestFactory)
                .executeEntrypointHooks(any(), any(), any());

        // when
        target.fromRequest(routingContext, 0L);

        // then
        final ArgumentCaptor<BidRequest> captor = ArgumentCaptor.forClass(BidRequest.class);
        verify(ortb2RequestFactory).enrichAuctionContext(any(), any(), captor.capture(), any(), anyLong());

        final BidRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getSite()).isNull();
        assertThat(capturedRequest.getApp()).isEqualTo(App.builder().bundle("org.company.application").build());
    }

    @Test
    public void shouldReturnFailedFutureIfEntrypointHooksRejectedRequest() {
        // given
        givenValidBidRequest(defaultBidRequest);

        final Throwable exception = new RuntimeException();
        doAnswer(invocation -> Future.failedFuture(exception))
                .when(ortb2RequestFactory)
                .executeEntrypointHooks(any(), any(), any());

        final AuctionContext auctionContext = AuctionContext.builder().requestRejected(true).build();
        doReturn(Future.succeededFuture(auctionContext))
                .when(ortb2RequestFactory)
                .restoreResultFromRejection(eq(exception));

        // when
        final Future<AuctionContext> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future).succeededWith(auctionContext);
    }

    @Test
    public void shouldUseBidRequestModifiedByRawAuctionRequestHooks() {
        // given
        givenValidBidRequest(BidRequest.builder()
                .site(Site.builder().domain("example.com").build())
                .build());

        final BidRequest modifiedBidRequest = BidRequest.builder()
                .app(App.builder().bundle("org.company.application").build())
                .build();
        doAnswer(invocation -> Future.succeededFuture(modifiedBidRequest))
                .when(ortb2RequestFactory)
                .executeRawAuctionRequestHooks(any());

        // when
        target.fromRequest(routingContext, 0L);

        // then
        final ArgumentCaptor<BidRequest> captor = ArgumentCaptor.forClass(BidRequest.class);
        verify(storedRequestProcessor).processStoredRequests(any(), captor.capture());

        final BidRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getSite()).isNull();
        assertThat(capturedRequest.getApp()).isEqualTo(App.builder().bundle("org.company.application").build());
    }

    @Test
    public void shouldReturnFailedFutureIfRawAuctionRequestHookFailedRequest() {
        // given
        givenValidBidRequest(defaultBidRequest);

        final Throwable exception = new RuntimeException();
        doAnswer(invocation -> Future.failedFuture(exception))
                .when(ortb2RequestFactory)
                .executeRawAuctionRequestHooks(any());

        final AuctionContext auctionContext = AuctionContext.builder().requestRejected(true).build();
        doReturn(Future.succeededFuture(auctionContext))
                .when(ortb2RequestFactory)
                .restoreResultFromRejection(eq(exception));

        // when
        final Future<AuctionContext> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future).succeededWith(auctionContext);
    }

    @Test
    public void shouldUseBidRequestModifiedByProcessedAuctionRequestHooks() {
        // given
        givenValidBidRequest(BidRequest.builder()
                .site(Site.builder().domain("example.com").build())
                .build());

        final BidRequest modifiedBidRequest = BidRequest.builder()
                .app(App.builder().bundle("org.company.application").build())
                .build();
        doAnswer(invocation -> Future.succeededFuture(modifiedBidRequest))
                .when(ortb2RequestFactory)
                .executeProcessedAuctionRequestHooks(any());

        // when
        final Future<AuctionContext> result = target.fromRequest(routingContext, 0L);

        // then

        final BidRequest resultBidRequest = result.result().getBidRequest();
        assertThat(resultBidRequest.getSite()).isNull();
        assertThat(resultBidRequest.getApp()).isEqualTo(App.builder().bundle("org.company.application").build());
    }

    @Test
    public void shouldReturnFailedFutureIfProcessedAuctionRequestHookRejectRequest() {
        // given
        givenValidBidRequest(defaultBidRequest);

        final Throwable exception = new RuntimeException();
        doAnswer(invocation -> Future.failedFuture(exception))
                .when(ortb2RequestFactory)
                .executeProcessedAuctionRequestHooks(any());

        final AuctionContext auctionContext = AuctionContext.builder().requestRejected(true).build();
        doReturn(Future.succeededFuture(auctionContext))
                .when(ortb2RequestFactory)
                .restoreResultFromRejection(eq(exception));

        // when
        final Future<AuctionContext> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future).succeededWith(auctionContext);
    }

    @Test
    public void shouldReturnFailedFutureIfEidsPermissionsContainsWrongDataType() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .data(ExtRequestPrebidData.of(emptyList(), null))
                        .build()))
                .build();

        final ObjectNode requestNode = mapper.convertValue(bidRequest, ObjectNode.class);
        final JsonNode eidPermissionNode = mapper.convertValue(
                ExtRequestPrebidDataEidPermissions.of("source", emptyList()), JsonNode.class);

        requestNode.with("ext").with("prebid").with("data").set("eidpermissions", eidPermissionNode);

        given(routingContext.getBodyAsString()).willReturn(requestNode.toString());

        // when
        final Future<?> result = target.fromRequest(routingContext, 0L);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(InvalidRequestException.class);
        assertThat(((InvalidRequestException) result.cause()).getMessages()).hasSize(1)
                .allSatisfy(message ->
                        assertThat(message).startsWith("Error decoding bidRequest: Cannot deserialize instance"));
    }

    @Test
    public void shouldReturnFailedFutureIfEidsPermissionsBiddersContainsWrongDataType() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .data(ExtRequestPrebidData.of(emptyList(), null))
                        .build()))
                .build();

        final ObjectNode requestNode = mapper.convertValue(bidRequest, ObjectNode.class);

        final ObjectNode eidPermissionNode = mapper.convertValue(
                ExtRequestPrebidDataEidPermissions.of("source", emptyList()), ObjectNode.class);

        eidPermissionNode.put("bidders", "notArrayValue");

        final ArrayNode arrayNode = requestNode
                .with("ext")
                .with("prebid")
                .with("data")
                .putArray("eidpermissions");
        arrayNode.add(eidPermissionNode);

        given(routingContext.getBodyAsString()).willReturn(requestNode.toString());

        // when
        final Future<?> result = target.fromRequest(routingContext, 0L);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(InvalidRequestException.class);
        assertThat(((InvalidRequestException) result.cause()).getMessages()).hasSize(1)
                .allSatisfy(message ->
                        assertThat(message).startsWith("Error decoding bidRequest: Cannot deserialize instance"));
    }

    @Test
    public void shouldTolerateMissingImpExtWhenProcessingAliases() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .imp(singletonList(Imp.builder().ext(null).build()))
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .aliases(singletonMap("alias", "bidder"))
                        .build()))
                .build();
        givenBidRequest(bidRequest);
        givenAuctionContext(bidRequest, defaultAccount);
        givenProcessStoredRequest(bidRequest);

        // when
        final Future<?> result = target.fromRequest(routingContext, 0L);

        // then
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    public void shouldCallOrtbFieldsResolver() {
        // given
        givenValidBidRequest(defaultBidRequest);

        // when
        target.fromRequest(routingContext, 0L).result();

        // then
        verify(ortbTypesResolver).normalizeBidRequest(any(), any(), any());
    }

    @Test
    public void shouldReturnFailedFutureIfOrtb2RequestFactoryReturnedFailedFuture() {
        // given
        givenValidBidRequest(BidRequest.builder().build());
        given(ortb2RequestFactory.fetchAccount(any())).willReturn(Future.failedFuture("error"));

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause()).hasMessage("error");
    }

    @Test
    public void shouldPassWebRequestTypeMetricToCreateAuctionContextWhenSiteIsPresent() {
        // given
        final BidRequest bidRequest = BidRequest.builder().site(Site.builder().build()).build();
        givenBidRequest(bidRequest);
        givenAuctionContext(bidRequest, Account.empty(ACCOUNT_ID));

        // when
        target.fromRequest(routingContext, 0L);

        // then
        verify(ortb2RequestFactory).enrichAuctionContext(
                any(), any(), eq(bidRequest), eq(MetricName.openrtb2web), anyLong());
    }

    @Test
    public void shouldPassAppRequestTypeMetricToCreateAccountContextWhenAppIsPresent() {
        // given
        final BidRequest bidRequest = BidRequest.builder().app(App.builder().build()).build();
        givenBidRequest(bidRequest);
        givenAuctionContext(bidRequest, Account.empty(ACCOUNT_ID));

        // when
        target.fromRequest(routingContext, 0L);

        // then
        verify(ortb2RequestFactory).enrichAuctionContext(
                any(), any(), eq(bidRequest), eq(MetricName.openrtb2app), anyLong());
    }

    @Test
    public void storedRequestProcessorShouldUseAccountIdFetchedByOrtb2RequestFactory() {
        // given
        givenValidBidRequest(defaultBidRequest);

        // when
        target.fromRequest(routingContext, 0L);

        // then
        verify(storedRequestProcessor).processStoredRequests(eq(ACCOUNT_ID), any());
    }

    @Test
    public void shouldReturnFailedFutureIfProcessStoredRequestsFailed() {
        // given
        givenValidBidRequest(defaultBidRequest);
        given(storedRequestProcessor.processStoredRequests(any(), any()))
                .willReturn(Future.failedFuture("error"));

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause()).hasMessage("error");
    }

    @Test
    public void shouldReturnFailedFutureIfRequestValidationFailed() {
        // given
        givenValidBidRequest(defaultBidRequest);

        given(ortb2RequestFactory.validateRequest(any()))
                .willThrow(new InvalidRequestException("errors"));

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause()).isInstanceOf(InvalidRequestException.class);
        assertThat(((InvalidRequestException) future.cause()).getMessages()).containsOnly("errors");
    }

    @Test
    public void shouldReturnAuctionContextWithExpectedParameters() {
        // given
        givenValidBidRequest(defaultBidRequest);

        // when
        final AuctionContext result = target.fromRequest(routingContext, 0L).result();

        // then
        assertThat(result).isEqualTo(defaultActionContext);
    }

    @Test
    public void shouldReturnModifiedBidRequestInAuctionContextWhenRequestWasPopulatedWithImplicitParams() {
        // given
        givenValidBidRequest(defaultBidRequest);

        final BidRequest updatedBidRequest = defaultBidRequest.toBuilder().id("updated").build();
        given(paramsResolver.resolve(any(), any(), any())).willReturn(updatedBidRequest);

        // when
        final AuctionContext result = target.fromRequest(routingContext, 0L).result();

        // then
        assertThat(result.getBidRequest()).isEqualTo(updatedBidRequest);
    }

    @Test
    public void shouldReturnPopulatedPrivacyContextAndGetWhenPrivacyEnforcementReturnContext() {
        // given
        givenValidBidRequest(defaultBidRequest);

        final GeoInfo geoInfo = GeoInfo.builder().vendor("vendor").city("found").build();
        final PrivacyContext privacyContext = PrivacyContext.of(
                Privacy.of("1", "consent", Ccpa.EMPTY, 0),
                TcfContext.builder().geoInfo(geoInfo).build());
        given(privacyEnforcementService.contextFromBidRequest(any()))
                .willReturn(Future.succeededFuture(privacyContext));

        // when
        final AuctionContext result = target.fromRequest(routingContext, 0L).result();

        // then
        assertThat(result.getPrivacyContext()).isEqualTo(privacyContext);
        assertThat(result.getGeoInfo()).isEqualTo(geoInfo);
    }

    private void givenBidRequest(BidRequest bidRequest) {
        try {
            given(routingContext.getBodyAsString()).willReturn(mapper.writeValueAsString(bidRequest));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void givenAuctionContext(BidRequest bidRequest, Account account) {
        given(ortb2RequestFactory.enrichAuctionContext(any(), any(), any(), any(), anyLong()))
                .willReturn(defaultActionContext.toBuilder()
                        .bidRequest(bidRequest)
                        .build());
        given(ortb2RequestFactory.fetchAccount(any())).willReturn(Future.succeededFuture(account));
    }

    private void givenProcessStoredRequest(BidRequest bidRequest) {
        given(storedRequestProcessor.processStoredRequests(any(), any()))
                .willReturn(Future.succeededFuture(bidRequest));
    }

    private void givenValidBidRequest(BidRequest bidRequest) {
        givenBidRequest(bidRequest);
        givenAuctionContext(bidRequest, defaultAccount);
        givenProcessStoredRequest(bidRequest);
    }

    private static String bidRequestToString(BidRequest bidRequest) {
        try {
            return mapper.writeValueAsString(bidRequest);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static Future<HttpRequestWrapper> toHttpRequest(RoutingContext routingContext, String body) {
        return Future.succeededFuture(HttpRequestWrapper.builder()
                .absoluteUri(routingContext.request().absoluteURI())
                .queryParams(routingContext.queryParams())
                .headers(routingContext.request().headers())
                .body(body)
                .scheme(routingContext.request().scheme())
                .remoteHost(routingContext.request().remoteAddress().host())
                .build());
    }
}
