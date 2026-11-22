package com.callagent.gateway.sip

import org.junit.Assert.assertTrue
import org.junit.Test

class SipAuthTest {
    @Test
    fun digestAuthIncludesQopNonceCountAndCnonce() {
        // RFC 2617 reference values, applied to the generic SIP builder.
        val header = SipAuth.buildAuthHeader(
            method = "GET",
            uri = "/dir/index.html",
            username = "Mufasa",
            password = "Circle Of Life",
            params = SipAuth.AuthParams(
                realm = "testrealm@host.com",
                nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
                opaque = "5ccc069c403ebaf9f0171e9517f40e41",
                qop = "auth"
            ),
            cnonceOverride = "0a4f113b",
            nonceCount = 1
        )

        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("nc=00000001"))
        assertTrue(header.contains("cnonce=\"0a4f113b\""))
    }
}
