package com.lynkcast.mirror.car;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREF = "carmirror";
    private static final String KEY_FILL = "fill";

    private FrameLayout root;
    private MirrorTextureView view;
    private LinearLayout panel;
    private EditText etIp;
    private Button btnConnect;
    private Button btnFind;
    private Button btnFit;
    private Button btnUpdate;
    private TextView tvState;
    private TextView tvLocal;

    private MirrorClient client;
    private AudioPlayer audioPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Surface decodeSurface;
    private boolean surfaceReady;
    private String pendingHost;
    private int fillMode = MirrorTextureView.MODE_CROP;
    private boolean manualCheck;

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
        view = findViewById(R.id.surface);
        panel = findViewById(R.id.panel);
        etIp = findViewById(R.id.et_ip);
        btnConnect = findViewById(R.id.btn_connect);
        btnFind = findViewById(R.id.btn_find);
        btnFit = findViewById(R.id.btn_fit);
        btnUpdate = findViewById(R.id.btn_update);
        tvState = findViewById(R.id.tv_state);
        tvLocal = findViewById(R.id.tv_local);

        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                decodeSurface = new Surface(st);
                surfaceReady = true;
                if (pendingHost != null) {
                    startClient(pendingHost);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                if (client != null) {
                    client.stop();
                }
                if (decodeSurface != null) {
                    decodeSurface.release();
                    decodeSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        String lastIp = sp.getString("last_ip", "");
        if (!lastIp.isEmpty()) {
            etIp.setText(lastIp);
        }
        fillMode = sp.getInt(KEY_FILL, MirrorTextureView.MODE_CROP);
        view.setMode(fillMode);
        updateFitLabel();

        client = new MirrorClient();
        audioPlayer = new AudioPlayer();

        btnConnect.setOnClickListener(v -> toggleConnect());
        btnFind.setOnClickListener(v -> doDiscover());
        btnFit.setOnClickListener(v -> {
            fillMode = (fillMode + 1) % 3;
            view.setMode(fillMode);
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(KEY_FILL, fillMode).apply();
            updateFitLabel();
        });
        btnUpdate.setOnClickListener(v -> {
            manualCheck = true;
            checkUpdate(v);
        });

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

        // 进界面静默查一次更新（失败不弹，免得没网时每次都打扰）
        handler.postDelayed(() -> {
            manualCheck = false;
            checkUpdate(null);
        }, 3000);
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
        if (decodeSurface != null) {
            decodeSurface.release();
            decodeSurface = null;
        }
        super.onDestroy();
    }

    private void updateFitLabel() {
        if (fillMode == MirrorTextureView.MODE_STRETCH) {
            btnFit.setText(R.string.btn_fit_stretch);
        } else if (fillMode == MirrorTextureView.MODE_ORIGINAL) {
            btnFit.setText(R.string.btn_fit_original);
        } else {
            btnFit.setText(R.string.btn_fit_crop);
        }
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
        if (decodeSurface == null) {
            return;
        }
        tvState.setText("连接中…");
        audioPlayer.stop();
        audioPlayer.connect(host);
        client.connect(host, MirrorClient.PORT, decodeSurface, new MirrorClient.Listener() {
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
                    view.setVideoSize(width, height);
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

    /** 查更新；v 非空表示用户手动点，需要给出反馈 */
    private void checkUpdate(View v) {
        btnUpdate.setEnabled(false);
        if (v != null) {
            tvState.setText("正在检查更新…");
        }
        UpdateCheck.check(this, new UpdateCheck.Callback() {
            @Override
            public void onUpdate(final int remoteCode, String tag, final String url) {
                handler.post(() -> {
                    btnUpdate.setEnabled(true);
                    if (manualCheck) {
                        tvState.setText("未连接");
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.update_title)
                            .setMessage("新版本 versionCode=" + remoteCode + "\n是否下载并安装？")
                            .setPositiveButton(R.string.update_download,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            UpdateCheck.downloadAndInstall(MainActivity.this, url);
                                        }
                                    })
                            .setNegativeButton(R.string.update_later, null)
                            .show();
                });
            }

            @Override
            public void onNothing(final boolean fail) {
                handler.post(() -> {
                    btnUpdate.setEnabled(true);
                    if (manualCheck) {
                        tvState.setText("未连接");
                        Toast.makeText(MainActivity.this,
                                fail ? R.string.update_failed : R.string.update_latest,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
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