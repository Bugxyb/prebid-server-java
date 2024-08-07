package org.prebid.server.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.proto.openrtb.ext.response.Events;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class EventsServiceTest {

    private EventsService eventsService;

    @BeforeEach
    public void setUp() {
        eventsService = new EventsService("http://external-url");
    }

    @Test
    public void createEventsShouldReturnExpectedEvent() {
        // given
        final EventsContext eventsContext = EventsContext.builder().auctionId("auctionId")
                .integration("pbjs")
                .auctionTimestamp(1000L)
                .build();

        // when
        final Events events = eventsService.createEvent("bidId", "bidder", "accountId", true, eventsContext);

        // then
        assertThat(events).isEqualTo(Events.of(
                "http://external-url/event?t=win&b=bidId&a=accountId&aid=auctionId&ts=1000&bidder=bidder&f=i&int=pbjs",
                "http://external-url/event?t=imp&b=bidId&a=accountId&aid=auctionId&ts=1000&bidder=bidder&f=i&int=pbjs"));
    }

    @Test
    public void createEventsShouldSetAnalyticsDisabled() {
        // given
        final EventsContext eventsContext = EventsContext.builder().integration("pbjs").auctionTimestamp(1000L).build();

        // when
        final Events events = eventsService.createEvent("bidId", "bidder", "accountId", false, eventsContext);

        // then
        assertThat(events).isEqualTo(Events.of(
                "http://external-url/event?t=win&b=bidId&a=accountId&ts=1000&bidder=bidder&f=i&int=pbjs&x=0",
                "http://external-url/event?t=imp&b=bidId&a=accountId&ts=1000&bidder=bidder&f=i&int=pbjs&x=0"));
    }

    @Test
    public void winUrlShouldReturnExpectedUrl() {
        // given
        final EventsContext eventsContext = EventsContext.builder().integration("pbjs").auctionTimestamp(1000L).build();

        // when
        final String winUrl = eventsService.winUrl("bidId", "bidder", "accountId", true, eventsContext);

        // then
        assertThat(winUrl).isEqualTo(
                "http://external-url/event?t=win&b=bidId&a=accountId&ts=1000&bidder=bidder&f=i&int=pbjs");
    }

    @Test
    public void winUrlShouldSEtAnalyticsDisabled() {
        // given
        final EventsContext eventsContext = EventsContext.builder().integration("pbjs").auctionTimestamp(1000L).build();

        // when
        final String winUrl = eventsService.winUrl("bidId", "bidder", "accountId", false, eventsContext);

        // then
        assertThat(winUrl).isEqualTo(
                "http://external-url/event?t=win&b=bidId&a=accountId&ts=1000&bidder=bidder&f=i&int=pbjs&x=0");
    }

    @Test
    public void vastUrlShouldReturnExpectedUrl() {
        // given
        final EventsContext eventsContext = EventsContext.builder().auctionId("auctionId")
                .integration("pbjs")
                .auctionTimestamp(1000L)
                .build();

        // when
        final String vastUrl = eventsService.vastUrlTracking("bidId", "bidder", "accountId", eventsContext);

        // then
        assertThat(vastUrl).isEqualTo(
                "http://external-url/event?t=imp&b=bidId&a=accountId&aid=auctionId&ts=1000&bidder=bidder&f=b&int=pbjs");
    }
}
