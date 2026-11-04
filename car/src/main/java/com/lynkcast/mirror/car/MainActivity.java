package com.lynkcast.mirror.car;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String PREF = "carmirror";

    private FrameLayout root;
    private AspectSurfaceView surfaceView;
    private LinearLayout panel;
    private EditText etIp;
    private Button btnConnect;
    private TextView tvState;

    private MirrorClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean surfaceReady;
    private String pendingHost;

    private final Runnable hidePanelTask = new Runnable() {
        @Override
        public void run() {
            panel.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        surfaceView = findViewById(R.id.surface);
        panel = findViewById(R.id.panel);
        etIp = findViewById(R.id.et_ip);
        btnConnect = findViewById(R.id.btn_connect);
        tvState = findViewById(R.id.tv_state);

        surfaceView.getHolder().addCallback(this);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        String lastIp = sp.getString("last_ip", "");
        if (!lastIp.isEmpty()) {
            etIp.setText(lastIp);
        }

        client = new MirrorClient();

        btnConnect.setOnClickListener(v -> toggleConnect());

        root.setOnClickListener(v -> {
            if (panel.getVisibility() == View.VISIBLE) {
                panel.setVisibility(View.GONE);
            } else {
                panel.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
        }
        super.onDestroy();
    }

    private void toggleConnect() {
        if (client.isRunning()) {
            client.stop();
            handler.postDelayed(this::resetUi, 300);
            return;
        }
        String host = etIp.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "请先输入手机地址", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("last_ip", host).apply();

        if (!surfaceReady) {
            pendingHost = host;
            tvState.setText("等待画面就绪…");
            return;
        }
        startClient(host);
    }

    private void startClient(String host) {
        pendingHost = null;
        tvState.setText("连接中…");
        client.connect(host, MirrorClient.PORT, surfaceView.getHolder().getSurface(),
                new MirrorClient.Listener() {
                    @Override
                    public void onState(String state) {
                        handler.post(() -> {
                            tvState.setText(state);
                            if ("投屏中".equals(state)) {
                                handler.removeCallbacks(hidePanelTask);
                                handler.postDelayed(hidePanelTask, 3000);
                            }
                        });
                    }

                    @Override
                    public void onVideoSize(int width, int height) {
                        handler.post(() -> {
                            surfaceView.setVideoRatio(width, height);
                            root.findViewById(R.id.tv_tip).setVisibility(View.GONE);
                        });
                    }

                    @Override
                    public void onDisconnected(final String reason) {
                        handler.post(() -> {
                            tvState.setText("已断开：" + reason);
                            panel.setVisibility(View.VISIBLE);
                            handler.removeCallbacks(hidePanelTask);
                        });
                    }
                });
    }

    private void resetUi() {
        tvState.setText("未连接");
        panel.setVisibility(View.VISIBLE);
        root.findViewById(R.id.tv_tip).setVisibility(View.VISIBLE);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingHost != null) {
            startClient(pendingHost);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (client != null) {
            client.stop();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
