package org.nieto.assistant.family
import org.junit.Assert.*
import org.junit.Test
class InvitationCodeTest {
 @Test fun ownLinksAndPlainCodesRoundtrip(){assertEquals("ABCDEF1234",InvitationCode.read(InvitationCode.link("ABCDEF1234")));assertEquals("ABCDEF1234",InvitationCode.read("ABCDEF1234"))}
 @Test fun foreignLinksAndInjectedFieldsAreRejected(){for(s in listOf("https://evil.example/?code=ABCDEF1234","tino://family/invite?code=ABCDEF1234&redirect=https://evil.example","tino://family/invite?code=ABCDEF1234#x","javascript:alert(1)","123")){assertThrows(Exception::class.java){InvitationCode.read(s)}}}
}
