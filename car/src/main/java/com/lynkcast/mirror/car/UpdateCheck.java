package com.lynkcast.mirror.car;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机端在线更新（与领克街机同一套做法）：
 *
 *   1. 读 Gitee 仓库里的 carmirror-info.txt，取 versionCode=NN 和 tag=xxx
 *   2. 用 tag 直连拼出 APK 地址 releases/download/<tag>/car-debug.apk（不走 API，避开匿名限流）
 *   3. 直链探测不通再看 Gitee Releases API 的附件列表
 *   4. 都比本地 versionCode 大才提示；下载校验长度与 zip 魔数后交系统安装器
 *
 * 不用 PackageInstaller 会话安装：荣耀/华为对那条路径兼容差，常报"安装失败 null"，
 * 改用 ACTION_VIEW 打开 APK 走系统标准安装界面。
 */
public class UpdateCheck {

    /** 版本信息（Gitee raw，国内直连） */
    private static final String GITEE_INFO =
            "https://gitee.com/ltnas/lynco/raw/master/carmirror-info.txt";
    /** Gitee releases/download 直链前缀 */
    private static final String GITEE_DL =
            "https://gitee.com/ltnas/lynco/releases/download";
    /** Gitee Releases API：直链探测失败时兜底取附件地址 */
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/ltnas/lynco/releases/latest";
    /** GitHub Release 兜底（未发 release 时忽略） */
    private static final String GH_APK =
            "https://github.com/killmebaby1/touping/releases/download/latest/car-debug.apk";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public interface Callback {
        /** remoteCode 大于本地时回调 */
        void onUpdate(int remoteCode, String tag, String url);

        /** fail=true：所有通道都连不上（网络问题）；false：检查成功且已是最新 */
        void onNothing(boolean fail);
    }

    /** 后台检查版本，回调发生在子线程。 */
    public static void check(final Activity act, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int localCode;
                try {
                    localCode = act.getPackageManager()
                            .getPackageInfo(act.getPackageName(), 0).versionCode;
                } catch (Exception e) {
                    cb.onNothing(true);
                    return;
                }
                log(act, "check start local=" + localCode);
                int remoteCode = -1;
                String tag = "";
                String url = "";
                try {
                    String body = httpGet(GITEE_INFO);
                    remoteCode = parseCode(body);
                    tag = parseTag(body);
                    log(act, "info code=" + remoteCode + " tag=" + tag);
                } catch (Exception e) {
                    log(act, "info fail " + e.getMessage());
                }
                if (tag.length() > 0) {
                    String dl = GITEE_DL + "/" + tag + "/car-debug.apk";
                    if (urlReachable(dl)) {
                        url = dl;
                        log(act, "apk direct ok");
                    } else {
                        log(act, "apk direct unreachable");
                    }
                }
                if (url.length() == 0) {
                    // 直链不通：从 API 的附件列表里按文件名挑 apk
                    try {
                        String text = httpGet(GITEE_API);
                        Matcher m = Pattern.compile(
                                "\"browser_download_url\":\"([^\"]+)\"")
                                .matcher(text);
                        while (m.find()) {
                            String cand = m.group(1).replace("\\/", "/");
                            if (cand.toLowerCase().endsWith(".apk")) {
                                url = cand;
                                break;
                            }
                        }
                        log(act, "api apk=" + url);
                    } catch (Exception e) {
                        log(act, "api fail " + e.getMessage());
                    }
                }
                if (url.length() == 0) {
                    url = GH_APK;
                }
                if (remoteCode > localCode) {
                    log(act, "UPDATE " + remoteCode + " > " + localCode);
                    cb.onUpdate(remoteCode, tag, url);
                } else {
                    log(act, "NOTHING remote=" + remoteCode + " local=" + localCode);
                    cb.onNothing(remoteCode <= 0);
                }
            }
        }, "cm-update").start();
    }

    /** 下载到应用外部目录（不需要存储权限）后交安装器。 */
    public static void downloadAndInstall(final Activity act, final String url) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage("正在下载更新包…");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(act.getExternalFilesDir(null), "update.apk");
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    log(act, "dl " + url);
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(120000);
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new Exception("HTTP " + code);
                    }
                    long expected = conn.getContentLengthLong();
                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    long written = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                    out.close();
                    in.close();
                    // 车机网络慢，容易"下载截断但不抛异常"，装出来就是解析失败
                    if (expected > 0 && written != expected) {
                        throw new Exception("下载不完整 " + written + "/" + expected);
                    }
                    FileInputStream fi = new FileInputStream(f);
                    byte[] head = new byte[4];
                    int hr = fi.read(head);
                    fi.close();
                    if (hr != 4 || head[0] != 'P' || head[1] != 'K'
                            || head[2] != 3 || head[3] != 4) {
                        throw new Exception("下载内容不是有效 APK（" + written + " 字节）");
                    }
                    log(act, "dl ok " + written + " bytes");
                    final File apk = f;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            install(act, apk);
                        }
                    });
                } catch (final Exception e) {
                    log(act, "dl fail " + e.getMessage());
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(act, "下载失败：" + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "cm-download").start();
    }

    private static void install(Context ctx, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            Toast.makeText(ctx, "请先允许「安装未知来源应用」，再点一次更新",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri contentUri = Uri.parse("content://com.lynkcast.mirror.car.updatefile"
                    + apk.getAbsolutePath());
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(contentUri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Toast.makeText(ctx, "已打开安装界面，请确认安装", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static boolean urlReachable(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 优先 versionCode=NN；没有才退回 (NN)；都没有才取最后一串数字。 */
    private static int parseCode(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        m = Pattern.compile("\\((\\d+)\\)").matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        int last = -1;
        m = Pattern.compile("(\\d+)").matcher(text);
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return last;
    }

    private static String parseTag(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?m)^\\s*tag=([A-Za-z0-9.\\-_]+)\\s*$").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 诊断日志，出问题让用户把这个文件发回来即可定位。 */
    private static void log(Context ctx, String s) {
        try {
            File f = new File(ctx.getExternalFilesDir(null), "update.log");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + s + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }
}