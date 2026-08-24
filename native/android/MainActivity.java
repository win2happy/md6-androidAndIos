package com.md6.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

/**
 * MD6 App 主 Activity
 * 
 * 扩展 Capacitor BridgeActivity：
 * 1. 注册 FSAccess 原生插件（使用 SAF 实现真正的文件选择器）
 * 2. 在应用启动时请求通知权限
 * 3. 延迟注入 FS Access API Polyfill 到 WebView
 * 
 * 注意：不覆盖 WebViewClient，避免破坏 Capacitor bridge 通信
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "MD6MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int POLYFILL_DELAY_MS = 1500; // 延迟注入，等待 Capacitor bridge 初始化

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 注册 FSAccess 原生插件（必须在 super.onCreate 之前）
        registerPlugin(FSAccessPlugin.class);

        super.onCreate(savedInstanceState);

        // 请求通知权限
        requestNotificationPermission();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 延迟注入 polyfill，确保 Capacitor bridge 和页面都已加载完成
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            injectFileSystemPolyfill();
        }, POLYFILL_DELAY_MS);
    }

    // ============================================================
    // 通知权限请求
    // ============================================================

    /**
     * 请求通知权限（Android 13+ 需要）
     * 
     * SAF 文件选择器不需要 READ/WRITE_EXTERNAL_STORAGE 权限，
     * 它通过 ACTION_OPEN_DOCUMENT_TREE Intent 启动系统选择器，
     * 用户授权后通过 URI 权限 (takePersistableUriPermission) 访问。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
                android.util.Log.i(TAG, "请求通知权限");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                android.util.Log.i(TAG, "权限已授予");
            } else {
                android.util.Log.w(TAG, "部分权限被拒绝，某些功能可能受限");
            }
        }
    }

    // ============================================================
    // WebView Polyfill 注入（不覆盖 WebViewClient）
    // ============================================================

    /**
     * 注入 File System Access API Polyfill 到 WebView
     * 
     * 使用 evaluateJavascript 直接注入，不覆盖 WebViewClient，
     * 避免 Capacitor bridge 通信被破坏。
     * 
     * polyfill 内部有防重复注入检查（window.__fsPolyfillInjected）
     */
    private void injectFileSystemPolyfill() {
        try {
            if (bridge != null && bridge.getWebView() != null) {
                WebView webView = bridge.getWebView();

                // 注入 FS Access API Polyfill（使用 FSAccess 原生插件）
                String polyfillScript = getFileSystemPolyfillScript();
                if (polyfillScript != null && !polyfillScript.isEmpty()) {
                    webView.evaluateJavascript(polyfillScript, null);
                    android.util.Log.i(TAG, "FS Access API Polyfill 已注入");
                }

                // 注入环境增强脚本
                String envScript = getCapacitorEnvScript();
                if (envScript != null && !envScript.isEmpty()) {
                    webView.evaluateJavascript(envScript, null);
                }
            } else {
                android.util.Log.w(TAG, "WebView 未就绪，无法注入 polyfill");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "注入 polyfill 失败", e);
        }
    }

    /**
     * 获取 File System Access API Polyfill 脚本
     * 
     * 使用 FSAccess 原生插件（SAF）实现真正的文件选择器
     */
    private String getFileSystemPolyfillScript() {
        return "(function() {" +
            "  if (window.__fsPolyfillInjected) return;" +
            "  window.__fsPolyfillInjected = true;" +
            "  " +
            "  function waitForCapacitor(callback) {" +
            "    if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FSAccess) {" +
            "      callback();" +
            "    } else {" +
            "      var interval = setInterval(function() {" +
            "        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FSAccess) {" +
            "          clearInterval(interval);" +
            "          callback();" +
            "        }" +
            "      }, 200);" +
            "      setTimeout(function() {" +
            "        clearInterval(interval);" +
            "        console.warn('[FS-Polyfill] 等待 FSAccess 插件超时，尝试回退方案');" +
            "        callback(true);" +
            "      }, 10000);" +
            "    }" +
            "  }" +
            "  " +
            "  waitForCapacitor(function(fallback) {" +
            "    if (fallback) {" +
            "      console.warn('[FS-Polyfill] FSAccess 插件不可用，使用简化回退方案');" +
            "      window.showDirectoryPicker = function() {" +
            "        return Promise.reject(new DOMException('File picker not available. Please restart the app.', 'NotSupportedError'));" +
            "      };" +
            "      window.showOpenFilePicker = function() {" +
            "        return Promise.reject(new DOMException('File picker not available. Please restart the app.', 'NotSupportedError'));" +
            "      };" +
            "      window.showSaveFilePicker = function() {" +
            "        return Promise.reject(new DOMException('File picker not available. Please restart the app.', 'NotSupportedError'));" +
            "      };" +
            "      window.dispatchEvent(new CustomEvent('fs-polyfill-ready', {detail: {nativePicker: false}}));" +
            "      return;" +
            "    }" +
            "    " +
            "    console.log('[FS-Polyfill] Capacitor FSAccess 插件就绪，安装 File System Access API Polyfill');" +
            "    " +
            "    var FSAccess = window.Capacitor.Plugins.FSAccess;" +
            "    " +
            "    // FileSystemFileHandle (SAF URI 版)" +
            "    function FSFileHandle(uri, name, size) {" +
            "      this.kind = 'file';" +
            "      this._uri = uri;" +
            "      this._name = name || 'unknown';" +
            "      this._size = size || 0;" +
            "    }" +
            "    FSFileHandle.prototype.getFile = function() {" +
            "      return FSAccess.readFile({uri: this._uri}).then(function(r) {" +
            "        var data = Uint8Array.from(atob(r.data), function(c) { return c.charCodeAt(0); });" +
            "        this._size = data.length;" +
            "        return new File([data], this._name, {type: 'application/octet-stream'});" +
            "      }.bind(this));" +
            "    };" +
            "    FSFileHandle.prototype.createWritable = function() {" +
            "      var self = this;" +
            "      var chunks = [];" +
            "      return Promise.resolve({" +
            "        write: function(data) {" +
            "          if (typeof data === 'string') data = new TextEncoder().encode(data);" +
            "          if (data instanceof Uint8Array) chunks.push(data);" +
            "        }," +
            "        close: function() {" +
            "          var total = chunks.reduce(function(a, c) { return a + c.length; }, 0);" +
            "          var combined = new Uint8Array(total);" +
            "          var off = 0;" +
            "          chunks.forEach(function(c) { combined.set(c, off); off += c.length; });" +
            "          var b64 = btoa(String.fromCharCode.apply(null, combined));" +
            "          return FSAccess.writeFile({uri: self._uri, data: b64});" +
            "        }" +
            "      });" +
            "    };" +
            "    FSFileHandle.prototype.isSameEntry = function(other) {" +
            "      return other instanceof FSFileHandle && other._uri === this._uri;" +
            "    };" +
            "    " +
            "    // FileSystemDirectoryHandle (SAF URI 版)" +
            "    function FSDirHandle(uri, name) {" +
            "      this.kind = 'directory';" +
            "      this._uri = uri;" +
            "      this._name = name || 'Directory';" +
            "    }" +
            "    FSDirHandle.prototype.getFileHandle = function(name, opts) {" +
            "      return FSAccess.listDirectory({uri: this._uri}).then(function(r) {" +
            "        var found = (r.entries || []).find(function(e) { return e.name === name && e.type === 'file'; });" +
            "        if (found) return new FSFileHandle(found.uri, found.name, found.size);" +
            "        throw new DOMException('File not found: ' + name, 'NotFoundError');" +
            "      });" +
            "    };" +
            "    FSDirHandle.prototype.getDirectoryHandle = function(name, opts) {" +
            "      return FSAccess.listDirectory({uri: this._uri}).then(function(r) {" +
            "        var found = (r.entries || []).find(function(e) { return e.name === name && e.type === 'directory'; });" +
            "        if (found) return new FSDirHandle(found.uri, found.name);" +
            "        throw new DOMException('Directory not found: ' + name, 'NotFoundError');" +
            "      });" +
            "    };" +
            "    FSDirHandle.prototype.removeEntry = function(name, opts) {" +
            "      return Promise.reject(new DOMException('Not supported with SAF', 'NotSupportedError'));" +
            "    };" +
            "    FSDirHandle.prototype.values = function() {" +
            "      var self = this;" +
            "      var entries = [];" +
            "      var index = 0;" +
            "      var loaded = false;" +
            "      return {" +
            "        next: function() {" +
            "          if (!loaded) {" +
            "            return FSAccess.listDirectory({uri: self._uri}).then(function(r) {" +
            "              loaded = true;" +
            "              var arr = r.entries || [];" +
            "              entries = arr.map(function(e) {" +
            "                return e.type === 'directory' ? new FSDirHandle(e.uri, e.name) : new FSFileHandle(e.uri, e.name, e.size);" +
            "              });" +
            "              if (entries.length > 0) return {value: entries[index++], done: false};" +
            "              return {value: undefined, done: true};" +
            "            });" +
            "          }" +
            "          if (index < entries.length) return Promise.resolve({value: entries[index++], done: false});" +
            "          return Promise.resolve({value: undefined, done: true});" +
            "        }," +
            "        [Symbol.asyncIterator]: function() { return this; }" +
            "      };" +
            "    };" +
            "    FSDirHandle.prototype.isSameEntry = function(other) {" +
            "      return other instanceof FSDirHandle && other._uri === this._uri;" +
            "    };" +
            "    " +
            "    // showDirectoryPicker - 使用 SAF 原生目录选择器" +
            "    window.showDirectoryPicker = function(opts) {" +
            "      var mode = (opts && opts.mode) || 'read';" +
            "      console.log('[FS-Polyfill] showDirectoryPicker 调用，mode=' + mode);" +
            "      return FSAccess.showDirectoryPicker({mode: mode}).then(function(r) {" +
            "        console.log('[FS-Polyfill] showDirectoryPicker 结果:', JSON.stringify(r));" +
            "        if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');" +
            "        return new FSDirHandle(r.uri, r.name);" +
            "      });" +
            "    };" +
            "    " +
            "    // showOpenFilePicker - 使用 SAF 原生文件选择器" +
            "    window.showOpenFilePicker = function(opts) {" +
            "      var multiple = (opts && opts.multiple) || false;" +
            "      console.log('[FS-Polyfill] showOpenFilePicker 调用');" +
            "      return FSAccess.showOpenFilePicker({multiple: multiple}).then(function(r) {" +
            "        if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');" +
            "        var files = (r.files || []).map(function(f) {" +
            "          return new FSFileHandle(f.uri, f.name, f.size);" +
            "        });" +
            "        return files;" +
            "      });" +
            "    };" +
            "    " +
            "    // showSaveFilePicker - 使用 SAF 原生保存选择器" +
            "    window.showSaveFilePicker = function(opts) {" +
            "      var suggestedName = (opts && opts.suggestedName) || 'untitled.txt';" +
            "      console.log('[FS-Polyfill] showSaveFilePicker 调用');" +
            "      return FSAccess.showSaveFilePicker({suggestedName: suggestedName}).then(function(r) {" +
            "        if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');" +
            "        return new FSFileHandle(r.uri, r.name);" +
            "      });" +
            "    };" +
            "    " +
            "    window.FileSystemFileHandle = FSFileHandle;" +
            "    window.FileSystemDirectoryHandle = FSDirHandle;" +
            "    " +
            "    // StorageManager.getDirectory polyfill" +
            "    if (navigator.storage && !navigator.storage.getDirectory) {" +
            "      navigator.storage.getDirectory = function() {" +
            "        return FSAccess.getPersistedUri().then(function(r) {" +
            "          if (r.uri) return new FSDirHandle(r.uri, 'Persisted Directory');" +
            "          throw new DOMException('No persisted directory', 'NotFoundError');" +
            "        });" +
            "      };" +
            "    }" +
            "    " +
            "    console.log('[FS-Polyfill] File System Access API Polyfill (SAF) 安装完成');" +
            "    window.dispatchEvent(new CustomEvent('fs-polyfill-ready', {detail: {nativePicker: true}}));" +
            "  });" +
            "})();";
    }

    /**
     * 获取 Capacitor 环境增强脚本
     */
    private String getCapacitorEnvScript() {
        return "(function() {" +
            "  if (window.__envScriptInjected) return;" +
            "  window.__envScriptInjected = true;" +
            "  " +
            "  window.__MD6_MOBILE__ = true;" +
            "  window.__MD6_PLATFORM__ = 'android';" +
            "  " +
            "  if (!navigator.permissions) {" +
            "    navigator.permissions = {" +
            "      query: function(desc) {" +
            "        return Promise.resolve({state: 'granted', onchange: null});" +
            "      }" +
            "    };" +
            "  }" +
            "  " +
            "  if (!window.origin) {" +
            "    window.origin = window.location.origin || 'https://md6.pnt.pp.ua';" +
            "  }" +
            "  " +
            "  console.log('[MD6-Env] Android 移动端环境增强脚本已注入');" +
            "})();";
    }
}