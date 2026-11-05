package com.lynkcast.mirror.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFY = 1002;

    private TextView tvIp;
    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            tvStatus.setText(MirrorService.statusText);
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvIp = findViewById(R.id.tv_ip);
        tvStatus = findViewById(R.id.tv_status);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        refreshAddresses();

        btnStart.setOnClickListener(v -> requestCapture());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, MirrorService.class);
            i.setAction(MirrorService.ACTION_STOP);
            startService(i);
            tvStatus.setText("已停止");
        });

        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAddresses();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    /**
     * 把本机所有 IPv4 都列出来。
     * 手机同时连着 Wi-Fi 又开热点时会有两个地址，让用户/车机自己看哪个对，
     * 不再由 App 猜一个。
     */
    private void refreshAddresses() {
        String all = StreamServer.describeAddresses();
        if (all.isEmpty()) {
            tvIp.setText("本机地址：未发现可用网络");
            MirrorService.localIp = "";
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本机地址（车机端点“自动查找”即可，不用手输）\n");
        sb.append(all);
        sb.append("\n\n端口 ").append(StreamServer.PORT);
        sb.append("　发现端口 ").append(StreamServer.DISCOVERY_PORT);
        tvIp.setText(sb.toString());

        String first = all.split("\n")[0];
        int idx = first.lastIndexOf("  ");
        MirrorService.localIp = idx >= 0 ? first.substring(idx + 2).trim() : first.trim();
    }

    private void requestCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未获得投屏授权", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MirrorService.class);
        intent.setAction(MirrorService.ACTION_START);
        intent.putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(MirrorService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "已开始投屏，可切到车机连接", Toast.LENGTH_SHORT).show();
    }
}