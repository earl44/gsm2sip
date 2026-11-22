package com.callagent.gateway.sip

import org.junit.Assert.assertTrue
import org.junit.Test

class SipOptionsTest {
    @Test
    fun finalOptionsResponseAddsToTagAndPreservesTransactionHeaders() {
        val request = SipMessage.parse(
            "OPTIONS sip:204@198.51.100.20:5062 SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 192.0.2.10:5060;rport;branch=z9hG4bK-test\r\n" +
                "To: <sip:204@198.51.100.20>\r\n" +
                "From: <sip:asterisk@192.0.2.10>;tag=pbx\r\n" +
                "Call-ID: qualify-test\r\n" +
                "CSeq: 42 OPTIONS\r\n" +
                "Content-Length: 0\r\n\r\n"
        )!!

        val response = SipBuilder.optionsResponse(
            request, "204", "198.51.100.20", 5062,
            "192.0.2.10" to 5060
        )

        assertTrue(response.startsWith("SIP/2.0 200 OK\r\n"))
        assertTrue(response.contains(
            "Via: SIP/2.0/UDP 192.0.2.10:5060;received=192.0.2.10;rport=5060;branch=z9hG4bK-test\r\n"
        ))
        assertTrue(response.contains("Call-ID: qualify-test\r\n"))
        assertTrue(response.contains("CSeq: 42 OPTIONS\r\n"))
        assertTrue(Regex("To: <sip:204@198\\.51\\.100\\.20>;tag=[^\\r\\n]+\\r\\n")
            .containsMatchIn(response))

        val retransmission = SipBuilder.optionsResponse(
            request, "204", "198.51.100.20", 5062,
            "192.0.2.10" to 5060
        )
        val to = response.lineSequence().first { it.startsWith("To:") }
        assertTrue(retransmission.contains("$to\r\n"))
    }
}
