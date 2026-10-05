package xyz.doikki.videoplayer.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.player.AbstractPlayer;

@SuppressLint("ViewConstructor")
public class TextureRenderView extends TextureView implements IRenderView, TextureView.SurfaceTextureListener {
    private MeasureHelper mMeasureHelper;
    private SurfaceTexture mSurfaceTexture;

    @Nullable
    private AbstractPlayer mMediaPlayer;
    private Surface mSurface;

    /** 交面之后的回调:效果链要求"先交面、后补输出尺寸",而纹理路径的面只在绘制阶段才出现 */
    @Nullable
    private Runnable mSurfaceReadyListener;

    /** 输出面尺寸(视频原生尺寸):效果链按它出画,显示侧再按视图缩放,与未开调色时一致 */
    private int mOutputWidth;
    private int mOutputHeight;

    public TextureRenderView(Context context) {
        super(context);
    }

    public void setOnSurfaceReadyListener(@Nullable Runnable listener) {
        mSurfaceReadyListener = listener;
    }

    /** 输出尺寸变化:改默认缓冲尺寸(否则管线按视图尺寸出画)并换面(否则输出 EGL 面仍按旧尺寸出画) */
    public void setOutputSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == mOutputWidth && height == mOutputHeight) return;
        mOutputWidth = width;
        mOutputHeight = height;
        applyOutputBufferSize();
        refreshSurface();
    }

    /** TextureView 自己会把默认缓冲尺寸改成视图尺寸(onSizeChanged / 建层时),这里改回输出尺寸 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyOutputBufferSize();
    }

    private void applyOutputBufferSize() {
        if (mSurfaceTexture == null || mOutputWidth <= 0 || mOutputHeight <= 0) return;
        mSurfaceTexture.setDefaultBufferSize(mOutputWidth, mOutputHeight);
    }

    /** 上一代 Surface:必须等输出 EGL 面切走之后再 release,先放会让 EGL 卡在已释放的 BufferQueue 上 */
    @Nullable
    private Surface mRetiredSurface;

    /** 换一个新 Surface(同一个 SurfaceTexture):输出 EGL 面只在交面/清面时才重建,尺寸变了不重建 */
    public boolean refreshSurface() {
        if (mSurfaceTexture == null || mMediaPlayer == null) return false;
        releaseRetiredSurface();
        mRetiredSurface = mSurface;
        mSurface = new Surface(mSurfaceTexture);
        mMediaPlayer.setSurface(mSurface);
        notifySurfaceReady();
        return true;
    }

    private void releaseRetiredSurface() {
        if (mRetiredSurface != null) {
            mRetiredSurface.release();
            mRetiredSurface = null;
        }
    }

    {
        mMeasureHelper = new MeasureHelper();
        setSurfaceTextureListener(this);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        if (mSurface != null) {
            player.setSurface(mSurface);
            notifySurfaceReady();
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
        return getBitmap();
    }

    @Override
    public void release() {
        releaseRetiredSurface();
        if (mSurface != null)
            mSurface.release();

        if (mSurfaceTexture != null)
            mSurfaceTexture.release();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (mSurfaceTexture != null) {
            setSurfaceTexture(mSurfaceTexture);
        } else {
            mSurfaceTexture = surfaceTexture;
            mSurface = new Surface(surfaceTexture);
            applyOutputBufferSize();
            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(mSurface);
                notifySurfaceReady();
            }
        }
    }

    private void notifySurfaceReady() {
        if (mSurfaceReadyListener != null) {
            mSurfaceReadyListener.run();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }
}