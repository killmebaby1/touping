package com.lynkcast.mirror.car;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * 按视频宽高比做等比缩放，避免画面被拉伸变形。
 */
public class AspectSurfaceView extends SurfaceView {

    private float ratio = 0f;

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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (ratio <= 0f || width <= 0 || height <= 0) {
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
