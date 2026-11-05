package com.lynkcast.mirror.car;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * 画面承载视图，两种摆放方式：
 *   铺满（默认）：按屏幕尺寸拉伸填满，不留黑边，比例不一致时会有一点变形
 *   原比例：等比居中，比例不一致时会出现黑边
 */
public class AspectSurfaceView extends SurfaceView {

    private float ratio = 0f;
    private boolean fill = true;

    public AspectSurfaceView(Context context) {
        super(context);
    }

    public AspectSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setVideoRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float newRatio = (float) width / height;
        if (Math.abs(newRatio - ratio) > 0.005f) {
            ratio = newRatio;
            requestLayout();
        }
    }

    public void setFill(boolean f) {
        if (fill != f) {
            fill = f;
            requestLayout();
        }
    }

    public boolean isFill() {
        return fill;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (fill || ratio <= 0f || width <= 0 || height <= 0) {
            setMeasuredDimension(width, height);
            return;
        }

        int newWidth = width;
        int newHeight = height;

        if ((float) width / height > ratio) {
            newWidth = (int) (height * ratio);
        } else {
            newHeight = (int) (width / ratio);
        }
        setMeasuredDimension(newWidth, newHeight);
    }
}