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
    private static final int REQ_AUDIO = 1003;

    private TextView tvIp;
    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;
    private Button btnDim;
    private Button btnUpdate;
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
        btnDim = findViewById(R.id.btn_dim);
        btnUpdate = findViewById(R.id.btn_update);

        refreshAddresses();

        btnStart.setOnClickListener(v -> requestCapture());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, MirrorService.class);
            i.setAction(MirrorService.ACTION_STOP);
            startService(i);
            tvStatus.setText("已停止");
        });

        btnDim.setOnClickListener(v -> toggleDim());
        btnUpdate.setOnClickListener(v -> checkUpdate());
        updateDimLabel();

        requestNotificationPermission();
    }

    private void checkUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        UpdateCheck.check(this, new UpdateCheck.Callback() {
            @Override
            public void onUpdate(int remoteCode, String tag, String url) {
                runOnUiThread(() -> showUpdate(tag, url));
            }

            @Override
            public void onNothing(boolean fail) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        fail ? "检查更新失败，请确认手机能上网" : "已是最新版本",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showUpdate(String tag, final String url) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage("是否下载并安装？")
                .setPositiveButton("下载并安装",
                        (d, w) -> UpdateCheck.downloadAndInstall(this, url))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void updateDimLabel() {
        boolean on = getSharedPreferences("carmirror_phone", MODE_PRIVATE)
                .getBoolean("dim_on", false);
        btnDim.setText(on ? R.string.btn_dim_on : R.string.btn_dim_off);
    }

    /** 暗屏省电开关：开启后每次投屏自动把亮度压到最低，停止投屏时恢复 */
    private void toggleDim() {
        if (Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.System.canWrite(this)) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "请允许“修改系统设置”，再回来开启暗屏", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "请到系统设置里允许本应用“修改系统设置”", Toast.LENGTH_LONG).show();
            }
        } else {
            boolean on = !getSharedPreferences("carmirror_phone", MODE_PRIVATE)
                    .getBoolean("dim_on", false);
            getSharedPreferences("carmirror_phone", MODE_PRIVATE)
                    .edit().putBoolean("dim_on", on).apply();
            updateDimLabel();
            Toast.makeText(this, on ? "已开启：投屏时手机自动压到最暗" : "已关闭暗屏",
                    Toast.LENGTH_SHORT).show();
        }
        updateDimLabel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAddresses();
        handler.post(refresher);
        updateDimLabel();
        // 开了暗屏却没给权限，是最常见的"按了没反应"，这里主动提醒
        if (Build.VERSION.SDK_INT >= 23
                && getSharedPreferences("carmirror_phone", MODE_PRIVATE).getBoolean("dim_on", false)
                && !android.provider.Settings.System.canWrite(this)) {
            Toast.makeText(this, "暗屏未生效：还没允许「修改系统设置」", Toast.LENGTH_LONG).show();
        }
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

    /** 先要录音权限（内录游戏声音用），没给也照样能投屏，只是没声音 */
    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_AUDIO);
            return;
        }
        startCaptureActivity();
    }

    private void startCaptureActivity() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            startCaptureActivity();
        }
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