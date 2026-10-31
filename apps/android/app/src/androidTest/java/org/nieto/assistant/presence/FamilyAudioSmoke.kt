package org.nieto.assistant.presence

import android.content.Context
import android.media.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** A synthetic tone: exercises Android's real AAC encoder and player without opening a microphone. */
internal fun familyAudioSmoke(context: Context): File {
    val pcm=java.nio.ByteBuffer.allocate(64000).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        repeat(32000){putShort((3000*sin(2*Math.PI*440*it/16000)).toInt().toShort())}
    }.array()
    val file=File(context.filesDir,"family-voice-fixture.m4a")
    val encoder=MediaCodec.createEncoderByType("audio/mp4a-latm")
    val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxing=false
    try {
        val format=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,24000)
        }
        encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
        var offset=0;var inputEnded=false;var done=false;var track=-1
        val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!done) {
            check(System.nanoTime()<deadline){"AAC encoder timed out"}
            if(!inputEnded) {
                val index=encoder.dequeueInputBuffer(10000)
                if(index>=0) {
                    val buffer=encoder.getInputBuffer(index)!!;buffer.clear()
                    val count=minOf(buffer.remaining(),pcm.size-offset)
                    buffer.put(pcm,offset,count)
                    val eos=count==0
                    encoder.queueInputBuffer(index,0,count,offset.toLong()*1000000/32000,if(eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    offset+=count;inputEnded=eos
                }
            }
            when(val index=encoder.dequeueOutputBuffer(info,10000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {track=muxer.addTrack(encoder.outputFormat);muxer.start();muxing=true}
                else -> if(index>=0) {
                    val buffer=encoder.getOutputBuffer(index)!!
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {check(muxing);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)}
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    encoder.releaseOutputBuffer(index,false)
                }
            }
        }
    } finally {
        runCatching{encoder.stop()};encoder.release()
        if(muxing)muxer.stop();muxer.release()
    }
    check(file.length() in 100..131072)
    val extractor=MediaExtractor()
    try {extractor.setDataSource(file.absolutePath);check(extractor.trackCount==1);check(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm")} finally {extractor.release()}
    val completed=CountDownLatch(1);var failed=false
    val player=MediaPlayer()
    try {
        player.setDataSource(file.absolutePath)
        player.setOnCompletionListener{completed.countDown()}
        player.setOnErrorListener{_,_,_->failed=true;completed.countDown();true}
        player.prepare();check(player.duration in 1500..3000);player.start()
        check(completed.await(8,TimeUnit.SECONDS) && !failed){"Native audio playback failed"}
    } finally {player.release()}
    return file
}
