package com.callagent.gateway.sip

import org.junit.Assert.assertTrue
import org.junit.Test

class SipIdentityTest {
    @Test
    fun outboundInviteUsesAccountInFromAndCallerInAssertedIdentity() {
        val invite = SipBuilder.invite(
            targetUri = "sip:600@192.0.2.10",
            username = "204",
            domain = "192.0.2.10",
            localIp = "198.51.100.20",
            localPort = 5062,
            callId = "call-test",
            cseq = 1,
            localRtpPort = 30000,
            callerIdNumber = "+36301234567",
            callerIdName = "GSM caller"
        )

        assertTrue(invite.contains("From: <sip:204@192.0.2.10>;tag="))
        assertTrue(invite.contains(
            "P-Asserted-Identity: \"GSM caller\" <sip:+36301234567@192.0.2.10>\r\n"
        ))
    }
}
