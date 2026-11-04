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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

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

        MirrorService.localIp = findIpv4();
        tvIp.setText("本机地址：" + (MirrorService.localIp.isEmpty() ? "未连接网络" : MirrorService.localIp)
                + ":" + StreamServer.PORT);

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
        MirrorService.localIp = findIpv4();
        tvIp.setText("本机地址：" + (MirrorService.localIp.isEmpty() ? "未连接网络" : MirrorService.localIp)
                + ":" + StreamServer.PORT);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
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

    /** 找本机一个可用 IPv4，优先 192.168 网段（手机热点） */
    private String findIpv4() {
        String fallback = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip == null) {
                        continue;
                    }
                    int pct = ip.indexOf('%');
                    if (pct > 0) {
                        ip = ip.substring(0, pct);
                    }
                    if (ip.startsWith("192.168.")) {
                        return ip;
                    }
                    if (fallback.isEmpty()) {
                        fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
