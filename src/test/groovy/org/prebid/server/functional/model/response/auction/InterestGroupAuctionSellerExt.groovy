package org.prebid.server.functional.model.response.auction

import groovy.transform.ToString

@ToString(includeNames = true, ignoreNulls = true)
class InterestGroupAuctionSellerExt {

    String bidder
    String adapter
}
