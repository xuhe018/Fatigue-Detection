package com.martin.ads.omoshiroilib.glessential;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.martin.ads.easymediacodec.TextureMovieEncoder;
import com.martin.ads.easymediacodec.VideoEncoderCore;
import com.martin.ads.omoshiroilib.camera.CameraEngine;
import com.martin.ads.omoshiroilib.camera.IWorkerCallback;
import com.martin.ads.omoshiroilib.codec.MediaAudioEncoder;
import com.martin.ads.omoshiroilib.codec.MediaEncoder;
import com.martin.ads.omoshiroilib.codec.MediaMuxerWrapper;
import com.martin.ads.omoshiroilib.codec.MediaVideoEncoder;
import com.martin.ads.omoshiroilib.debug.removeit.GlobalConfig;
import com.martin.ads.omoshiroilib.encoder.gles.EGLFilterDispatcher;
import com.martin.ads.omoshiroilib.encoder.gles.GLTextureSaver;
import com.martin.ads.omoshiroilib.filter.base.AbsFilter;
import com.martin.ads.omoshiroilib.filter.base.FilterGroup;
import com.martin.ads.omoshiroilib.filter.base.OESFilter;
import com.martin.ads.omoshiroilib.filter.base.OrthoFilter;
import com.martin.ads.omoshiroilib.filter.base.PassThroughFilter;
import com.martin.ads.omoshiroilib.filter.helper.FilterFactory;
import com.martin.ads.omoshiroilib.filter.helper.FilterType;
import com.martin.ads.omoshiroilib.util.BitmapUtils;
import com.martin.ads.omoshiroilib.util.FileUtils;

import java.io.File;
import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

//import com.martin.ads.omoshiroilib.filter.effect.*;
//import com.martin.ads.omoshiroilib.filter.effect.mx.*;
//import com.martin.ads.omoshiroilib.filter.ext.*;
//import com.martin.ads.omoshiroilib.filter.imgproc.*;

/**
 * Created by Ads on 2017/1/26.
 */

public class GLRender implements GLSurfaceView.Renderer {
    private static final String TAG = "GLRender";

    private CameraEngine cameraEngine;
    private FilterGroup filterGroup;
    private FilterGroup postProcessFilters;
    private GLTextureSaver lastProcessFilter;
    private OESFilter oesFilter;
    private Context context;
    private FilterGroup customizedFilters;

    private FilterType currentFilterType=FilterType.NONE;

    private int surfaceWidth;
    private int surfaceHeight;
    private boolean isCameraFacingFront;

    private OrthoFilter orthoFilter;

    private MediaVideoEncoder mVideoEncoder;

    public GLRender(final Context context, CameraEngine cameraEngine) {
        this.context=context;
        this.cameraEngine=cameraEngine;
        filterGroup=new FilterGroup();
        postProcessFilters=new FilterGroup();
        oesFilter=new OESFilter(context);
        filterGroup.addFilter(oesFilter);
        orthoFilter=new OrthoFilter(context);
        if(GlobalConfig.FULL_SCREEN)
            filterGroup.addFilter(orthoFilter);

        customizedFilters=new FilterGroup();
        customizedFilters.addFilter(FilterFactory.createFilter(currentFilterType,context));

        postProcessFilters.addFilter(new PassThroughFilter(context));
        lastProcessFilter= new GLTextureSaver(context);
        postProcessFilters.addFilter(lastProcessFilter);

        filterGroup.addFilter(customizedFilters);
        filterGroup.addFilter(postProcessFilters);

        cameraEngine.setPictureTakenCallBack(new PictureTakenCallBack() {
            @Override
            public void saveAsBitmap(final byte[] data) {
                Log.d(TAG, "onPictureTaken - jpeg, size: " + data.length);
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                Log.d(TAG, "onPictureTaken: "+bitmap.getWidth()+" "+bitmap.getHeight());
                final File pictureFolderPath = new File(
                        Environment.getExternalStorageDirectory(), "/Omoshiroi/pictures");
                if (!pictureFolderPath.exists())
                    pictureFolderPath.mkdirs();
                File outputFile= FileUtils.makeTempFile(pictureFolderPath.getAbsolutePath(),"IMG_", ".jpg");
                IWorkerCallback workerCallback=new IWorkerCallback() {
                    @Override
                    public void onPostExecute(Exception exception) {
                        if (exception == null) {
                            Log.d(TAG, "Picture saved to disk - jpeg, size: " + data.length);
                        }
                    }
                };
                //BitmapUtils.saveBitmap(bitmap,outputFile.getAbsolutePath()+".jpg",workerCallback);

                //BitmapUtils.saveByteArray(data, outputFile.getAbsolutePath(), workerCallback);
                BitmapUtils.saveBitmapWithFilterApplied(GlobalConfig.context,currentFilterType,bitmap,outputFile.getAbsolutePath(),workerCallback);

            }
        });
        isCameraFacingFront=true;

    }

    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        filterGroup.init();
    }

    @Override
    public void onDrawFrame(GL10 glUnused) {
        long timeStamp=cameraEngine.doTextureUpdate(oesFilter.getSTMatrix());
        filterGroup.onDrawFrame(oesFilter.getGlOESTexture().getTextureId());
        if(mVideoEncoder!=null){
            Log.d(TAG, "onDrawFrame: "+mVideoEncoder.toString());
            mVideoEncoder.frameAvailableSoon();
        }
    }


    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private EGLContext getSharedContext() {
        return EGL14.eglGetCurrentContext();
    }

    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged: "+width+" "+height);
        this.surfaceWidth=width;
        this.surfaceHeight=height;
        GLES20.glViewport(0,0,width,height);
        filterGroup.onFilterChanged(width,height);
        if(cameraEngine.isCameraOpened()){
            cameraEngine.stopPreview();
            cameraEngine.releaseCamera();
        }
        cameraEngine.setTexture(oesFilter.getGlOESTexture().getTextureId());
        cameraEngine.openCamera(isCameraFacingFront);
        cameraEngine.startPreview();
    }

    public void onPause(){
        if(cameraEngine.isCameraOpened()){
            cameraEngine.stopPreview();
            cameraEngine.releaseCamera();
        }
    }

    public void onResume() {
    }

    public void onDestroy(){
        if(cameraEngine.isCameraOpened()){
            cameraEngine.releaseCamera();
        }
    }

    public void switchCamera(){
        isCameraFacingFront=!isCameraFacingFront;
        cameraEngine.switchCamera(isCameraFacingFront);
    }

    public interface PictureTakenCallBack{
        void saveAsBitmap(final byte[] data);
    }

    public void switchLastFilterOfCustomizedFilters(FilterType filterType){
        if (filterType==null) return;
        currentFilterType=filterType;
        customizedFilters.switchLastFilter(FilterFactory.createFilter(filterType,context));
    }

    public void switchFilterOfPostProcessAtPos(AbsFilter filter,int pos){
        if (filter==null) return;
        postProcessFilters.switchFilterAt(filter,pos);
    }

    public FilterGroup getFilterGroup() {
        return filterGroup;
    }

    public OrthoFilter getOrthoFilter() {
        return orthoFilter;
    }

    private MediaMuxerWrapper mMuxer;
    public static final boolean DEBUG=true;
    private String outputPath;
    FileUtils.FileSavedCallback fileSavedCallback;

    public void startRecording() {
        try {
            File vidFolder=GlobalConfig.context.getCacheDir();
            if (!vidFolder.exists())
                vidFolder.mkdirs();
            outputPath=vidFolder.getAbsolutePath()+FileUtils.getVidName();
            mMuxer = new MediaMuxerWrapper(outputPath);	// if you record audio only, ".m4a" is also OK.
            if (true) {
                // for video capturing
                new MediaVideoEncoder(mMuxer, mMediaEncoderListener, /*surfaceWidth/2*2,surfaceHeight/2*2*/720,1280);
            }
            if (true) {
                // for audio capturing
                new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
            }
            mMuxer.prepare();
            mMuxer.startRecording();
        } catch (final IOException e) {
            Log.e(TAG, "startCapture:", e);
        }
    }

    /**
     * request stop recording
     */
    public void stopRecording() {
        if (mMuxer != null) {
            mMuxer.stopRecording();
            mMuxer = null;
            mVideoEncoder=null;
            if(fileSavedCallback!=null)
                fileSavedCallback.onFileSaved(outputPath);
        }
    }

    public void setVideoEncoder(final MediaVideoEncoder encoder) {
        filterGroup.addPreDrawTask(new Runnable() {
            @Override
            public void run() {
                if (encoder != null) {
                    encoder.getRenderHandler().setEglDrawer(new EGLFilterDispatcher(context));
                    encoder.setEglContext(getSharedContext(), lastProcessFilter.getSavedTextureId());
                    mVideoEncoder=encoder;
                }
            }
        });
    }

    /**
     * callback methods from encoder
     */
    private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(final MediaEncoder encoder) {
            if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
            if (encoder instanceof MediaVideoEncoder)
                setVideoEncoder((MediaVideoEncoder)encoder);
        }

        @Override
        public void onStopped(final MediaEncoder encoder) {
            if (DEBUG) Log.v(TAG, "onStopped:encoder=" + encoder);
            if (encoder instanceof MediaVideoEncoder)
                setVideoEncoder(null);
        }
    };

    public void setFileSavedCallback(FileUtils.FileSavedCallback fileSavedCallback) {
        this.fileSavedCallback = fileSavedCallback;
    }
}
