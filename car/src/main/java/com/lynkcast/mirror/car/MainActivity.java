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
    private Button btnFind;
    private TextView tvState;
    private TextView tvLocal;

    private MirrorClient client;
    private AudioPlayer audioPlayer;
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
        btnFind = findViewById(R.id.btn_find);
        tvState = findViewById(R.id.tv_state);
        tvLocal = findViewById(R.id.tv_local);

        surfaceView.getHolder().addCallback(this);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        String lastIp = sp.getString("last_ip", "");
        if (!lastIp.isEmpty()) {
            etIp.setText(lastIp);
        }

        client = new MirrorClient();
        audioPlayer = new AudioPlayer();

        btnConnect.setOnClickListener(v -> toggleConnect());
        btnFind.setOnClickListener(v -> doDiscover());

        String local = MirrorClient.describeLocalAddresses();
        tvLocal.setText(local.isEmpty()
                ? "车机未联网，先连手机热点"
                : "车机 " + local.replace("\n", " / "));

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
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        super.onDestroy();
    }

    /** 局域网广播找手机，找到直接连 */
    private void doDiscover() {
        if (client.isRunning()) {
            return;
        }
        btnFind.setEnabled(false);
        tvState.setText("正在局域网内查找手机…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String host = MirrorClient.discover(2500);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnFind.setEnabled(true);
                        if (host == null) {
                            tvState.setText("没找到手机：确认车机连的是手机热点，且手机端已点开始投屏");
                            return;
                        }
                        etIp.setText(host);
                        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                                .putString("last_ip", host).apply();
                        if (!surfaceReady) {
                            pendingHost = host;
                            tvState.setText("找到手机 " + host + "，等待画面就绪…");
                            return;
                        }
                        tvState.setText("找到手机 " + host + "，连接中…");
                        startClient(host);
                    }
                });
            }
        }, "cm-discover").start();
    }

    private void toggleConnect() {
        if (client.isRunning()) {
            client.stop();
            audioPlayer.stop();
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
        audioPlayer.stop();
        audioPlayer.connect(host);
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
                            audioPlayer.stop();
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
