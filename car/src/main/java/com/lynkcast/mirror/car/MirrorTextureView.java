package com.lynkcast.mirror.car;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * 画面承载视图。解码器直接把帧解到它的 Surface，显示方式由本类的 Matrix 控制：
 *
 *   MODE_CROP    裁剪填满：等比放大到覆盖全屏，多出来的边被裁掉。无黑边、不变形（默认）
 *   MODE_STRETCH 拉伸铺满：横纵各自拉伸填满全屏。无黑边，但比例不一致时会变形
 *   MODE_ORIGINAL 原比例：等比缩小到完整可见。不变形，但比例不一致时留黑边
 *
 * 换 TextureView 而不用 SurfaceView，是因为 SurfaceView 的画面缩放由 SurfaceFlinger
 * 按 layer 自行处理，应用无法干预；车机屏幕和手机比例不同时只能听天由命。
 */
public class MirrorTextureView extends TextureView {

    public static final int MODE_CROP = 0;
    public static final int MODE_STRETCH = 1;
    public static final int MODE_ORIGINAL = 2;

    private int mode = MODE_CROP;
    private int videoW;
    private int videoH;

    public MirrorTextureView(Context context) {
        super(context);
    }

    public MirrorTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMode(int m) {
        if (mode != m) {
            mode = m;
            applyTransform();
        }
    }

    public int getMode() {
        return mode;
    }

    public void setVideoSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        videoW = w;
        videoH = h;
        applyTransform();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTransform();
    }

    private void applyTransform() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) {
            return;
        }
        // TextureView 默认把纹理拉伸铺满整个 view，这里把"默认缩放"算出来，
        // 再按需要的模式换算成相对它的修正系数。
        float sx = (float) vw / videoW;
        float sy = (float) vh / videoH;
        Matrix m = new Matrix();
        if (mode == MODE_ORIGINAL) {
            float s = Math.min(sx, sy);
            m.setScale(s / sx, s / sy, vw / 2f, vh / 2f);
        } else if (mode == MODE_CROP) {
            float s = Math.max(sx, sy);
            m.setScale(s / sx, s / sy, vw / 2f, vh / 2f);
        }
        // MODE_STRETCH：默认行为即是拉伸铺满，矩阵保持单位阵
        setTransform(m);
    }

    /** SurfaceTexture 是否已就绪（供 Activity 判断能否开始连接）。 */
    public boolean isReady() {
        SurfaceTexture st = getSurfaceTexture();
        return st != null && isAvailable();
    }
}