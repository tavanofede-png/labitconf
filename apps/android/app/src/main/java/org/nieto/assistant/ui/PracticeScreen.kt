package org.nieto.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.nieto.assistant.R
import org.nieto.assistant.NietoApp
import org.nieto.assistant.demo.PracticeFlow
import org.nieto.assistant.demo.PracticeSession
import java.util.UUID

private val PracticeGreen=Color(0xFF007D65)
private val PracticeCyan=Color(0xFF00D6C9)
private val SessionSaver=Saver<PracticeSession,String>(save={ s -> JSONObject().put("flow",s.flow.name).put("step",s.step).put("contacts",org.json.JSONArray(s.contacts.toList())).put("groupName",s.groupName).put("to",s.to).put("subject",s.subject).put("body",s.body).put("reviewed",s.reviewed).toString() },restore={ raw -> val j=JSONObject(raw);PracticeSession(PracticeFlow.valueOf(j.getString("flow")),j.getInt("step"),j.getJSONArray("contacts").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },j.getString("groupName"),j.getString("to"),j.getString("subject"),j.getString("body"),j.getBoolean("reviewed")) })

/** An explicitly labelled, offline exercise. Every highlighted control is interactive. */
@Composable fun PracticeScreen(app: NietoApp,flow: PracticeFlow,onClose:()->Unit) {
    var session by rememberSaveable(flow,stateSaver=SessionSaver) { mutableStateOf(PracticeSession(flow)) }
    var attempt by rememberSaveable(flow) { mutableStateOf(UUID.randomUUID().toString()) }
    var confirmExit by remember { mutableStateOf(false) }
    val scroll=rememberScrollState()
    val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val leave={ if(session.step in 1..5)confirmExit=true else onClose() }
    BackHandler { leave() }
    LaunchedEffect(session.step) { scroll.scrollTo(0);app.speak(session.instruction) }
    LaunchedEffect(session.complete,attempt) { if(session.complete)app.recordPractice(flow,attempt) }
    DisposableEffect(Unit) { onDispose { app.stopSpeaking() } }
    Scaffold(containerColor=Color(0xFFF3F6F6),topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onClose) { Image(painterResource(R.drawable.tino_logo),"Volver al chat de TINO",Modifier.width(100.dp).height(44.dp)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick=leave) { Text("Cerrar ×",color=Color(0xFF4C5B61)) }
        }
    },bottomBar={
        Surface(color=Color(0xFFF3F6F6)) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=20.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                if(!keyboardOpen) Column(Modifier.widthIn(max=720.dp)) {
                Surface(color=Color(0xFFE0F7F3),shape=RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(R.drawable.tino_mascot),null,Modifier.size(48.dp))
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(session.instruction,fontSize=18.sp,lineHeight=27.sp,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite });TextButton(onClick={app.speak(session.instruction)}) { Text("↻ Repetir indicación",color=PracticeGreen) } }
                    }
                }
                }
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=720.dp).fillMaxSize().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.tino_mascot),null,Modifier.size(64.dp))
                    Column { Text("PRÁCTICA GUIADA",color=PracticeGreen,fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);Text(flow.title,fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics { heading() }) }
                }
                Text("Un espacio para aprender. Los mensajes y grupos de esta práctica no se envían fuera de TINO.",color=Color(0xFF4C5B61),fontSize=14.sp,lineHeight=21.sp)
                LinearProgressIndicator(progress={session.step/6f},modifier=Modifier.fillMaxWidth(),color=PracticeGreen,trackColor=Color(0xFFD9E5E2))
                Text(if(session.complete)"Práctica completada" else "Paso ${session.step+1} de 6",color=PracticeGreen,fontSize=14.sp)
                Surface(shape=RoundedCornerShape(24.dp),color=Color.White,border=BorderStroke(1.dp,Color(0xFFDDE6E4)),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        when(session.stage) {
                            "launch" -> {
                                Text("Tu pantalla de inicio",fontWeight=FontWeight.SemiBold,fontSize=20.sp)
                                PracticeAction(if(flow==PracticeFlow.WHATSAPP)"WhatsApp" else "Correo",onClick={session=session.next()})
                            }
                            "chats" -> {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("WhatsApp · Chats",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));OutlinedButton(onClick={session=session.next()},border=BorderStroke(3.dp,PracticeGreen),modifier=Modifier.heightIn(min=56.dp)) { Text("+ Nuevo chat") } }
                                listOf("Familia","Amigos del barrio","Ana Ríos").forEach { Text(it,Modifier.padding(vertical=10.dp));HorizontalDivider(color=Color(0xFFE3E6E6)) }
                            }
                            "new_group" -> { Text("Nuevo chat",fontSize=22.sp,fontWeight=FontWeight.Bold);PracticeAction("Nuevo grupo",onClick={session=session.next()});Text("Contactos de ejemplo",color=Color(0xFF4C5B61));PracticeSession.PEOPLE.forEach { Text(it,Modifier.padding(8.dp)) } }
                            "people" -> {
                                Text("Elegí a las personas",fontSize=22.sp,fontWeight=FontWeight.Bold)
                                Surface(shape=RoundedCornerShape(16.dp),border=BorderStroke(2.dp,PracticeGreen),color=Color.White) { Column(Modifier.padding(8.dp)) { PracticeSession.PEOPLE.forEach { person -> Row(Modifier.fillMaxWidth().toggleable(value=person in session.contacts,role=Role.Checkbox,onValueChange={session=session.toggleContact(person)}),verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=person in session.contacts,onCheckedChange=null);Text(person) } } } }
                                Text(if(session.contacts.size==1)"1 persona seleccionada" else "${session.contacts.size} personas seleccionadas",color=PracticeGreen);PracticeAction("Continuar",onClick={session=session.next()})
                            }
                            "name" -> { Text("Poné un nombre al grupo",fontSize=22.sp,fontWeight=FontWeight.Bold);PracticeField("Nombre del grupo",session.groupName,{session=session.edit("groupName",it)});PracticeAction("Continuar",onClick={session=session.next()}) }
                            "inbox" -> { Text("Correo · Recibidos",fontSize=22.sp,fontWeight=FontWeight.Bold);listOf("Ana · Nos vemos mañana","Biblioteca · Tu reserva está lista","Familia · Fotos del fin de semana").forEach { Text(it,Modifier.padding(vertical=8.dp));HorizontalDivider() };PracticeAction("+ Redactar",onClick={session=session.next()}) }
                            "to" -> { Text("¿A quién le escribís?",fontSize=22.sp,fontWeight=FontWeight.Bold);PracticeField("Para",session.to,{session=session.edit("to",it)},KeyboardType.Email);PracticeAction("Continuar",onClick={session=session.next()}) }
                            "subject" -> { Text("¿De qué trata tu mail?",fontSize=22.sp,fontWeight=FontWeight.Bold);PracticeField("Asunto",session.subject,{session=session.edit("subject",it)});PracticeAction("Continuar",onClick={session=session.next()}) }
                            "body" -> { Text("Escribí tu mensaje",fontSize=22.sp,fontWeight=FontWeight.Bold);PracticeField("Mensaje",session.body,{session=session.edit("body",it)},multiline=true);PracticeAction("Revisar mi mail",onClick={session=session.next()}) }
                            "review","done" -> {
                                Text(if(session.complete)if(flow==PracticeFlow.WHATSAPP)"Grupo de práctica creado" else "Email de práctica enviado" else "Revisemos antes de terminar",fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold)
                                if(flow==PracticeFlow.WHATSAPP) { Text(session.groupName,fontWeight=FontWeight.SemiBold,fontSize=20.sp);Text(session.contacts.joinToString(" · ")) }
                                else { Text("Para: ${session.to}");Text(session.subject,fontWeight=FontWeight.SemiBold,fontSize=20.sp);Text(session.body,lineHeight=25.sp) }
                                if(!session.complete) {
                                    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=session.reviewed,onCheckedChange={session=session.copy(reviewed=it,error=null)});Text(if(flow==PracticeFlow.WHATSAPP)"Revisé el nombre y las personas" else "Revisé destinatario, asunto y mensaje",fontSize=15.sp) }
                                    PracticeAction(if(flow==PracticeFlow.WHATSAPP)"Crear grupo de práctica" else "Enviar mail de práctica",enabled=session.reviewed,onClick={session=session.next()})
                                } else {
                                    Text("¡Lo hiciste! Podés repetir los pasos a tu ritmo.",color=PracticeGreen)
                                    PracticeAction("Volver al chat",onClick=onClose)
                                    TextButton(onClick={attempt=UUID.randomUUID().toString();session=PracticeSession(flow)}) { Text("Practicar otra vez") }
                                }
                            }
                        }
                        session.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
                    }
                }
                if(session.step in 1..5)TextButton(onClick={session=session.back()}) { Text("← Paso anterior",color=PracticeGreen) }
            }
        }
    }
    if(confirmExit)AlertDialog(onDismissRequest={confirmExit=false},title={Text("¿Salir de la práctica?")},text={Text("Podés volver a empezar cuando quieras.")},confirmButton={TextButton(onClick={confirmExit=false;onClose()}){Text("Salir")}},dismissButton={TextButton(onClick={confirmExit=false}){Text("Seguir practicando")}})
}

@Composable private fun PracticeAction(label:String,enabled:Boolean=true,onClick:()->Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=58.dp),shape=RoundedCornerShape(16.dp),border=if(enabled)BorderStroke(3.dp,PracticeGreen)else null,colors=ButtonDefaults.buttonColors(containerColor=PracticeCyan,contentColor=Color(0xFF101416)),contentPadding=PaddingValues(16.dp)) { Text(label,fontSize=18.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable private fun PracticeField(label:String,value:String,onChange:(String)->Unit,keyboard:KeyboardType=KeyboardType.Text,multiline:Boolean=false) {
    val requester=remember { BringIntoViewRequester() }
    val scope=rememberCoroutineScope()
    val keyboardController=LocalSoftwareKeyboardController.current
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged { if(it.isFocused)scope.launch { delay(300);requester.bringIntoView() } },minLines=if(multiline)4 else 1,maxLines=if(multiline)8 else 1,singleLine=!multiline,keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=if(multiline)ImeAction.Default else ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboardController?.hide()}),shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=PracticeGreen,unfocusedBorderColor=PracticeGreen))
}
