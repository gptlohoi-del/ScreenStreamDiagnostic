package com.example.screenstreamdiag;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.SystemClock;
import java.nio.ByteBuffer;

/** Captures mono PCM, encodes AAC-LC and delivers individual access units to the RTSP server. */
public final class AudioEngine {
    public static final String NONE="none", MIC="mic", PLAYBACK="playback";
    public static final int RATE=44100, CHANNELS=1, BITRATE=64000;
    public interface Sink { void onAac(byte[] aac, long ptsUs); }
    private final AudioRecord record;
    private final MediaCodec encoder;
    private final Sink sink;
    private final String mode;
    private volatile boolean running;
    private Thread worker;

    public AudioEngine(Context context, MediaProjection projection, String requestedMode, Sink sink) throws Exception {
        if (NONE.equals(requestedMode)) throw new IllegalArgumentException("No audio requested");
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("RECORD_AUDIO was not granted");
        if (PLAYBACK.equals(requestedMode) && Build.VERSION.SDK_INT<29)
            throw new IllegalArgumentException("Internal playback capture requires Android 10+");
        this.mode=requestedMode;
        this.sink=sink;
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0) throw new IllegalStateException("AudioRecord min buffer unavailable: "+min);
        AudioFormat inputFormat=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        AudioRecord candidate=null;
        MediaCodec candidateCodec=null;
        try {
            AudioRecord.Builder builder=new AudioRecord.Builder().setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(Math.max(min*2,8192));
            if(PLAYBACK.equals(requestedMode)) {
                AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME).build();
                builder.setAudioPlaybackCaptureConfig(config);
            } else {
                builder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            candidate=builder.build();
            if(candidate.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("AudioRecord init failed");
            candidateCodec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat fmt=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,RATE,CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE,BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
            candidateCodec.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            candidateCodec.start();
            record=candidate;encoder=candidateCodec;
            DiagnosticLog.event("AUDIO","INIT source="+mode+" AAC-LC "+RATE+"Hz mono bitrate="+BITRATE);
        }catch(Exception ex){
            if(candidateCodec!=null){try{candidateCodec.release();}catch(Exception ignored){}}
            if(candidate!=null)candidate.release();
            throw ex;
        }
    }
    public void start(){
        if(running)return;
        running=true;
        worker=new Thread(this::runAudio,"aac-capture-encode");worker.start();
    }
    private void runAudio(){
        long samples=0, lastReport=SystemClock.elapsedRealtime(), packets=0;
        byte[] pcm=new byte[2048];
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try {
            record.startRecording();
            if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not start");
            DiagnosticLog.event("AUDIO","START source="+mode);
            while(running){
                int n=record.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<0)throw new IllegalStateException("AudioRecord.read code="+n);
                if(n>0){
                    int idx=encoder.dequeueInputBuffer(10000);
                    if(idx>=0){
                        ByteBuffer input=encoder.getInputBuffer(idx);
                        if(input!=null){
                            input.clear();int size=Math.min(n,input.remaining());size-=size%2;
                            input.put(pcm,0,size);
                            encoder.queueInputBuffer(idx,0,size,samples*1000000L/RATE,0);
                            samples+=size/2;
                        }
                    }
                }
                for(int i=0;i<8;i++){
                    int outputIndex=encoder.dequeueOutputBuffer(info,0);
                    if(outputIndex<0)break;
                    try{
                        if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                            ByteBuffer b=encoder.getOutputBuffer(outputIndex);
                            if(b!=null){
                                b.position(info.offset);b.limit(info.offset+info.size);
                                byte[] au=new byte[info.size];b.get(au);
                                sink.onAac(au,info.presentationTimeUs);
                                packets++;
                            }
                        }
                    }finally{encoder.releaseOutputBuffer(outputIndex,false);}
                }
                long now=SystemClock.elapsedRealtime();
                if(now-lastReport>=10000){
                    DiagnosticLog.event("AUDIO_HEALTH","source="+mode+" aacFrames="+packets+
                            " capturedSamples="+samples);
                    lastReport=now;packets=0;
                }
            }
        }catch(Exception e){
            if(running)DiagnosticLog.error("AUDIO_FAILED",e);
        }finally{
            running=false;
            DiagnosticLog.event("AUDIO","STOP source="+mode);
        }
    }
    public void stop(){
        running=false;
        try{record.stop();}catch(Exception ignored){}
        if(worker!=null && worker!=Thread.currentThread())try{worker.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        try{encoder.stop();}catch(Exception ignored){}
        try{encoder.release();}catch(Exception ignored){}
        record.release();
    }
    public boolean isRunning(){return running;}
    public String mode(){return mode;}
}
