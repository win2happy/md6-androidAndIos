package com.md6.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * FSAccess 原生插件
 * 
 * 使用 Android Storage Access Framework (SAF) 实现真正的文件选择器
 * - ACTION_OPEN_DOCUMENT_TREE: 选择目录
 * - ACTION_OPEN_DOCUMENT: 选择文件
 * - ACTION_CREATE_DOCUMENT: 保存文件
 * 
 * 支持通过 content URI 读写文件，持久化 URI 权限
 */
@CapacitorPlugin(
    name = "FSAccess",
    permissions = {
        @Permission(
            strings = { "android.permission.READ_EXTERNAL_STORAGE" },
            alias = "read"
        ),
        @Permission(
            strings = { "android.permission.WRITE_EXTERNAL_STORAGE" },
            alias = "write"
        ),
        @Permission(
            strings = { "android.permission.POST_NOTIFICATIONS" },
            alias = "notifications"
        )
    }
)
public class FSAccessPlugin extends Plugin {

    private static final String TAG = "FSAccessPlugin";

    // Intent request codes
    private static final int REQUEST_DIR_PICKER = 1001;
    private static final int REQUEST_FILE_PICKER = 1002;
    private static final int REQUEST_SAVE_PICKER = 1003;

    // 保存当前挂起的 Capacitor 调用
    private PluginCall savedCall;

    // 持久化的 URI 权限存储
    private static final String PREFS_NAME = "fs_access_prefs";
    private static final String KEY_DIR_URI = "persisted_dir_uri";

    @Override
    public void load() {
        Log.d(TAG, "FSAccess Plugin loaded");
    }

    // ============================================================
    // showDirectoryPicker - 使用 SAF 打开目录选择器
    // ============================================================

    @PluginMethod
    public void showDirectoryPicker(PluginCall call) {
        String mode = call.getString("mode", "read");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("readwrite".equals(mode)) {
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }

            savedCall = call;
            startActivityForResult(call, intent, REQUEST_DIR_PICKER);
        } else {
            // Android 5.0 以下回退
            call.reject("SAF not supported on this Android version");
        }
    }

    // ============================================================
    // showOpenFilePicker - 使用 SAF 打开文件选择器
    // ============================================================

    @PluginMethod
    public void showOpenFilePicker(PluginCall call) {
        Boolean multiple = call.getBoolean("multiple", false);
        JSONArray types = call.getArray("types");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (multiple && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            savedCall = call;
            startActivityForResult(call, intent, REQUEST_FILE_PICKER);
        } else {
            call.reject("SAF not supported on this Android version");
        }
    }

    // ============================================================
    // showSaveFilePicker - 使用 SAF 创建/保存文件
    // ============================================================

    @PluginMethod
    public void showSaveFilePicker(PluginCall call) {
        String suggestedName = call.getString("suggestedName", "untitled.txt");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            savedCall = call;
            startActivityForResult(call, intent, REQUEST_SAVE_PICKER);
        } else {
            call.reject("SAF not supported on this Android version");
        }
    }

    // ============================================================
    // readFile - 通过 content URI 读取文件
    // ============================================================

    @PluginMethod
    public void readFile(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null) {
            call.reject("uri is required");
            return;
        }

        try {
            Uri uri = Uri.parse(uriString);
            ContentResolver resolver = getContext().getContentResolver();
            InputStream is = resolver.openInputStream(uri);

            if (is == null) {
                call.reject("Cannot open file: " + uriString);
                return;
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[16384];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            is.close();

            byte[] bytes = buffer.toByteArray();
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            JSObject result = new JSObject();
            result.put("data", base64);
            result.put("size", bytes.length);

            // 获取文件名
            String fileName = getFileName(uri);
            result.put("name", fileName != null ? fileName : "unknown");

            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "readFile failed", e);
            call.reject("Failed to read file: " + e.getMessage());
        }
    }

    // ============================================================
    // writeFile - 通过 content URI 写入文件
    // ============================================================

    @PluginMethod
    public void writeFile(PluginCall call) {
        String uriString = call.getString("uri");
        String base64Data = call.getString("data");

        if (uriString == null || base64Data == null) {
            call.reject("uri and data are required");
            return;
        }

        try {
            Uri uri = Uri.parse(uriString);
            byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);

            ContentResolver resolver = getContext().getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);

            if (os == null) {
                call.reject("Cannot open file for writing: " + uriString);
                return;
            }

            os.write(bytes);
            os.flush();
            os.close();

            JSObject result = new JSObject();
            result.put("size", bytes.length);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "writeFile failed", e);
            call.reject("Failed to write file: " + e.getMessage());
        }
    }

    // ============================================================
    // listDirectory - 列出目录内容
    // ============================================================

    @PluginMethod
    public void listDirectory(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null) {
            call.reject("uri is required");
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            call.reject("SAF not supported on this Android version");
            return;
        }

        try {
            Uri uri = Uri.parse(uriString);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri));

            ContentResolver resolver = getContext().getContentResolver();
            Cursor cursor = resolver.query(
                childrenUri,
                new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null, null, null
            );

            JSONArray entries = new JSONArray();

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    long size = cursor.getLong(3);
                    long lastModified = cursor.getLong(4);

                    boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);

                    // 构建子 URI
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId);

                    JSObject entry = new JSObject();
                    entry.put("name", name);
                    entry.put("uri", childUri.toString());
                    entry.put("type", isDirectory ? "directory" : "file");
                    entry.put("mimeType", mimeType);
                    entry.put("size", size);
                    entry.put("lastModified", lastModified);

                    entries.put(entry);
                }
                cursor.close();
            }

            JSObject result = new JSObject();
            result.put("entries", entries);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "listDirectory failed", e);
            call.reject("Failed to list directory: " + e.getMessage());
        }
    }

    // ============================================================
    // getPersistedUri - 获取持久化的目录 URI
    // ============================================================

    @PluginMethod
    public void getPersistedUri(PluginCall call) {
        String uri = getContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DIR_URI, null);

        JSObject result = new JSObject();
        result.put("uri", uri);
        call.resolve(result);
    }

    // ============================================================
    // requestPermissions - 请求所有必要权限
    // ============================================================

    @PluginMethod
    public void requestAllPermissions(PluginCall call) {
        requestAllPermissions(call);
    }

    // ============================================================
    // Activity Result 处理
    // ============================================================

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        if (savedCall == null) {
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            // 用户取消选择
            JSObject result = new JSObject();
            result.put("cancelled", true);
            savedCall.resolve(result);
            savedCall = null;
            return;
        }

        switch (requestCode) {
            case REQUEST_DIR_PICKER:
                handleDirectoryPickerResult(data);
                break;
            case REQUEST_FILE_PICKER:
                handleFilePickerResult(data);
                break;
            case REQUEST_SAVE_PICKER:
                handleSavePickerResult(data);
                break;
        }
    }

    /**
     * 处理目录选择器结果
     */
    private void handleDirectoryPickerResult(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            JSObject result = new JSObject();
            result.put("cancelled", true);
            savedCall.resolve(result);
            savedCall = null;
            return;
        }

        // 持久化 URI 权限
        try {
            ContentResolver resolver = getContext().getContentResolver();
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            resolver.takePersistableUriPermission(uri, takeFlags);

            // 保存到 SharedPreferences
            getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DIR_URI, uri.toString())
                .apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist URI permission", e);
        }

        // 获取目录名
        String dirName = getFileName(uri);

        JSObject result = new JSObject();
        result.put("uri", uri.toString());
        result.put("name", dirName != null ? dirName : "Selected Directory");
        result.put("type", "directory");
        result.put("cancelled", false);

        savedCall.resolve(result);
        savedCall = null;
    }

    /**
     * 处理文件选择器结果
     */
    private void handleFilePickerResult(Intent data) {
        JSONArray files = new JSONArray();

        // 处理多选
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                JSObject file = createFileResult(uri);
                files.put(file);
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            JSObject file = createFileResult(uri);
            files.put(file);
        }

        JSObject result = new JSObject();
        result.put("files", files);
        result.put("cancelled", false);

        savedCall.resolve(result);
        savedCall = null;
    }

    /**
     * 处理保存文件选择器结果
     */
    private void handleSavePickerResult(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            JSObject result = new JSObject();
            result.put("cancelled", true);
            savedCall.resolve(result);
            savedCall = null;
            return;
        }

        // 持久化 URI 权限
        try {
            ContentResolver resolver = getContext().getContentResolver();
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            resolver.takePersistableUriPermission(uri, takeFlags);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist URI permission", e);
        }

        String fileName = getFileName(uri);

        JSObject result = new JSObject();
        result.put("uri", uri.toString());
        result.put("name", fileName != null ? fileName : "untitled");
        result.put("type", "file");
        result.put("cancelled", false);

        savedCall.resolve(result);
        savedCall = null;
    }

    /**
     * 创建文件结果对象
     */
    private JSObject createFileResult(Uri uri) {
        String fileName = getFileName(uri);
        long fileSize = getFileSize(uri);

        JSObject file = new JSObject();
        file.put("uri", uri.toString());
        file.put("name", fileName != null ? fileName : "unknown");
        file.put("size", fileSize);
        file.put("type", "file");

        return file;
    }

    /**
     * 从 URI 获取文件名
     */
    private String getFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = null;

        try {
            ContentResolver resolver = getContext().getContentResolver();
            cursor = resolver.query(uri, null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get file name", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // 回退：从 URI 路径提取
        if (fileName == null) {
            String path = uri.getLastPathSegment();
            if (path != null) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0) {
                    fileName = path.substring(lastSlash + 1);
                } else {
                    fileName = path;
                }
            }
        }

        return fileName;
    }

    /**
     * 从 URI 获取文件大小
     */
    private long getFileSize(Uri uri) {
        long size = 0;
        Cursor cursor = null;

        try {
            ContentResolver resolver = getContext().getContentResolver();
            cursor = resolver.query(uri, null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get file size", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return size;
    }
}