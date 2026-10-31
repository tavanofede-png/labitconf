package org.nieto.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.nieto.assistant.family.InvitationCode

@Composable internal fun InvitationQr(code:String) {
    val bitmap=remember(code){runCatching{
        val matrix=QRCodeWriter().encode(InvitationCode.link(code),BarcodeFormat.QR_CODE,512,512)
        Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply{val pixels=IntArray(512*512){i->if(matrix[i%512,i/512])android.graphics.Color.BLACK else android.graphics.Color.WHITE};setPixels(pixels,0,512,0,0,512,512)}
    }.getOrNull()}
    DisposableEffect(bitmap){onDispose{bitmap?.recycle()}}
    bitmap?.let{Image(it.asImageBitmap(),"QR de invitación familiar, válido por 15 minutos",Modifier.size(200.dp))}
}
internal fun readInvitationImage(context:Context,uri:Uri):String {
    require(uri.scheme=="content")
    val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}
    require(options.outWidth>0 && options.outHeight>0)
    options.inSampleSize=1
    while(options.outWidth/options.inSampleSize>2048 || options.outHeight/options.inSampleSize>2048)options.inSampleSize*=2
    options.inJustDecodeBounds=false
    val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)} ?: error("No pude leer esa imagen.")
    try {val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val reader=MultiFormatReader();val result=reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        return InvitationCode.read(result.text)
    } finally {bitmap.recycle()}
}
