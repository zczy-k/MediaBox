package com.github.tvbox.osc.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.LOG;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.MeasureHelper;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    private MeasureHelper mMeasureHelper;

    private AbstractPlayer mMediaPlayer;

    public SurfaceRenderView(Context context) {
        super(context);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mMeasureHelper = new MeasureHelper();
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setFormat(PixelFormat.RGBA_8888);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        SurfaceHolder holder = getHolder();
        boolean valid = holder.getSurface() != null && holder.getSurface().isValid();
        // 「有声无画」的关键埋点：这一行告诉我们"交给内核的那一刻，渲染面到底存不存在"。
        // SurfaceView 的 surface 由系统异步创建：视图刚被挂进容器就 attach 的话 valid=false，
        // 此时只能等 surfaceCreated 回调补交 —— 那次补交如果没发生(或发生在旧内核上)，
        // 声音照常播、位置照常走、一个错误都不报，屏幕就是一片黑。
        LOG.i("echo-play-verify layer=surface attach holderValid=" + valid);
        if (valid) {
            player.setDisplay(holder);
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        setRotation(degree);
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public Bitmap doScreenShot() {
        return null;
    }

    @Override
    public void release() {

    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 有没有这一行 = 系统到底有没有把渲染面交回来。
        // 有声无画最常见的一种就是它**根本没发生**(视图没被挂进窗口/被 detach 后 surface 已销毁)，
        // 而内核那边一切"正常"：进播放态、位置推进、零报错。
        LOG.i("echo-play-verify layer=surface created hasPlayer=" + (mMediaPlayer != null));
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        LOG.i("echo-play-verify layer=surface changed " + width + "x" + height + " hasPlayer=" + (mMediaPlayer != null));
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
    }
}
