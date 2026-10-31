package org.nieto.assistant.family

import java.net.URI
import java.net.URLDecoder

object InvitationCode {
    fun read(value:String):String {
        val text=value.trim()
        val code=if(text.startsWith("tino://family/invite?")) {
            val uri=URI(text)
            require(uri.host=="family" && uri.path=="/invite" && uri.fragment==null)
            val parts=uri.rawQuery.split('&').map{it.split('=',limit=2)}
            require(parts.size==1 && parts[0].size==2 && parts[0][0]=="code")
            URLDecoder.decode(parts[0][1],"UTF-8")
        } else text
        require(Regex("[A-Z0-9_-]{5,32}").matches(code)){"El QR no contiene una invitación válida de TINO."}
        return code
    }
    fun link(code:String)="tino://family/invite?code="+read(code)
}
