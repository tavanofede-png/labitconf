package org.nieto.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun InstitutionExportDialog(initialStart: Long?,initialEnd: Long?,close: ()->Unit,export: (Long,Long,String)->Unit) {
    val calendar=rememberDateRangePickerState(initialSelectedStartDateMillis=initialStart ?: LocalDate.of(2026,5,1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),initialSelectedEndDateMillis=initialEnd ?: LocalDate.of(2026,10,31).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val start=calendar.selectedStartDateMillis;val end=calendar.selectedEndDateMillis
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=840.dp).fillMaxWidth(.96f).fillMaxHeight(.95f),shape=RoundedCornerShape(24.dp),color=DashPaper) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Exportar reporte",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold,color=DashInk)
                    TextButton(onClick=close) { Text("Cerrar",color=DashTeal) }
                }
                Text("Elegí la fecha inicial y final",Modifier.padding(horizontal=20.dp),color=DashMuted)
                DateRangePicker(state=calendar,modifier=Modifier.weight(1f).fillMaxWidth(),showModeToggle=true,title={Text("Período del reporte",Modifier.padding(start=24.dp,top=12.dp),color=DashMuted)},headline={
                    Text(if(start!=null && end!=null) "${InstitutionReport.date(start)} - ${InstitutionReport.date(end)}" else "Seleccioná ambas fechas",Modifier.padding(horizontal=24.dp,vertical=12.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=DashInk)
                },colors=DatePickerDefaults.colors(containerColor=DashPaper,selectedDayContainerColor=DashCyan,selectedDayContentColor=DashInk,dayInSelectionRangeContainerColor=DashCyan.copy(alpha=.17f),dayInSelectionRangeContentColor=DashInk))
                Text("Datos de demostración: las cifras son fijas. El rango elegido aparece en ambos archivos.",Modifier.padding(horizontal=20.dp,vertical=8.dp),fontSize=12.sp,color=DashMuted)
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={if(start!=null && end!=null)export(start,end,"csv")},enabled=start!=null && end!=null,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Guardar CSV",color=DashTeal) }
                    Button(onClick={if(start!=null && end!=null)export(start,end,"pdf")},enabled=start!=null && end!=null,modifier=Modifier.weight(1f).heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=DashCyan,contentColor=DashInk)) { Text("Guardar PDF") }
                }
            }
        }
    }
}
