package com.lynkcast.mirror.car;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 自实现 FileProvider（工程无 androidx 依赖），把更新包以 content:// 交给系统安装器。
 * URI：content://com.lynkcast.mirror.car.updatefile/<文件绝对路径>
 *
 * 不直接用 PackageInstaller 会话安装：荣耀/华为等 ROM 对该路径兼容差，
 * 用 ACTION_VIEW 打开 APK 走系统标准安装界面最通用。
 */
public class UpdateFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(uri.getPath());
        if (!f.exists() || !f.canRead()) {
            throw new FileNotFoundException("no file: " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}