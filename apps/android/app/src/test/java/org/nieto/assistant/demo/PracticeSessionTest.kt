package org.nieto.assistant.demo

import org.junit.Assert.*
import org.junit.Test

class PracticeSessionTest {
    @Test fun whatsappRequiresParticipantsNameAndAnExplicitReview() {
        var s=PracticeSession(PracticeFlow.WHATSAPP)
        repeat(3){s=s.next()};assertEquals("people",s.stage)
        assertEquals(3,s.next().step)
        s=s.toggleContact("Ana Ríos").next();assertEquals("name",s.stage)
        assertEquals(4,s.next().step)
        s=s.edit("groupName","Familia LABITCONF").next();assertEquals(5,s.next().step)
        s=s.copy(reviewed=true).next();assertTrue(s.complete)
        assertEquals("Familia LABITCONF",s.groupName);assertEquals(1,s.contacts.size)
    }
    @Test fun emailRejectsInvalidAddressAndEmptyFieldsAndNeverSkipsReview() {
        var s=PracticeSession(PracticeFlow.EMAIL).next().next()
        for(value in listOf("","correo","a@","a@b","a b@example.com"))assertEquals(2,s.edit("to",value).next().step)
        s=s.edit("to","jueces@example.com").next();assertEquals(3,s.next().step)
        s=s.edit("subject","Demo TINO").next();assertEquals(4,s.next().step)
        s=s.edit("body","La tech, de todos.").next();assertEquals(5,s.next().step)
        s=s.copy(reviewed=true).next();assertTrue(s.complete)
        assertFalse(s.edit("to","otro@example.com").complete)
        assertFalse(s.edit("body","Texto cambiado").reviewed)
    }
    @Test fun repeatedJourneysAndBacktrackingRemainIndependentAcrossOneThousandRuns() {
        repeat(1000){i->
            for(flow in PracticeFlow.entries){
                var s=PracticeSession(flow)
                assertEquals(0,s.back().step)
                repeat(if(flow==PracticeFlow.WHATSAPP)3 else 2){s=s.next()}
                if(flow==PracticeFlow.WHATSAPP)s=s.toggleContact("Ana Ríos").toggleContact("Pedro López").next().edit("groupName","Grupo $i").next()
                else s=s.edit("to","juez$i@example.com").next().edit("subject","Prueba $i").next().edit("body","Mensaje $i").next()
                assertEquals("review",s.stage);assertFalse(s.next().complete)
                val previous=s.back();assertFalse(previous.reviewed);assertFalse(previous.complete)
                assertTrue(s.copy(reviewed=true).next().complete)
                assertFalse(PracticeSession(flow).complete)
            }
        }
    }
}
