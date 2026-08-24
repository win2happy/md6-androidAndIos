package com.md6.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import com.getcapacitor.BridgeActivity;

/**
 * MD6 App 主 Activity
 * 
 * 扩展 Capacitor BridgeActivity：
 * 1. 注册 FSAccess 原生插件（使用 SAF 实现真正的文件选择器）
 * 2. 在应用启动时请求运行时权限（存储 + 通知）
 * 3. 注入 FS Access API Polyfill 到 WebView
 */
public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 注册 FSAccess 原生插件（必须在 super.onCreate 之前）
        registerPlugin(FSAccessPlugin.class);

        super.onCreate(savedInstanceState);

        // 请求运行时权限
        requestAppPermissions();
    }

    @Override
    public void onResume() {
        super.onResume();
        injectFileSystemPolyfill();
    }

    // ============================================================
    // 运行时权限请求
    // ============================================================

    /**
     * 请求应用所需的运行时权限
     * - Android 13+ (API 33): READ_MEDIA_* 替代 READ_EXTERNAL_STORAGE
     * - Android 13+ (API 33): POST_NOTIFICATIONS 通知权限
     * - Android 10-12: READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
     */
    private void requestAppPermissions() {
        // 需要请求的权限列表
        java.util.ArrayList<String> permissionsNeeded = new java.util.ArrayList<>();

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用细化的媒体权限
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12 及以下
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // 请求未授予的权限
        if (!permissionsNeeded.isEmpty()) {
            String[] permissionsArray = permissionsNeeded.toArray(new String[0]);
            requestPermissions(permissionsArray, PERMISSION_REQUEST_CODE);
            android.util.Log.i("MD6MainActivity", "请求权限: " + permissionsNeeded);
        } else {
            android.util.Log.i("MD6MainActivity", "所有权限已授予");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // 检查权限请求结果
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                android.util.Log.i("MD6MainActivity", "所有权限已授予");
            } else {
                android.util.Log.w("MD6MainActivity", "部分权限被拒绝，某些功能可能受限");
            }
        }
    }

    // ============================================================
    // WebView Polyfill 注入
    // ============================================================

    /**
     * 注入 File System Access API Polyfill 到 WebView
     * 
     * 在页面加载完成后注入 polyfill JavaScript，
     * 使远程网页能够使用 FS Access API（通过 FSAccess 原生插件）
     */
    private void injectFileSystemPolyfill() {
        try {
            if (bridge != null && bridge.getWebView() != null) {
                WebView webView = bridge.getWebView();
                
                // 设置 WebView 允许文件访问
                webView.getSettings().setAllowFileAccess(true);
                webView.getSettings().setAllowContentAccess(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setJavaScriptEnabled(true);
                
                // 注入 polyfill 脚本 - 在每个页面加载时执行
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        
                        // 注入 FS Access API Polyfill（使用 FSAccess 原生插件）
                        String polyfillScript = getFileSystemPolyfillScript();
                        if (polyfillScript != null && !polyfillScript.isEmpty()) {
                            view.evaluateJavascript(polyfillScript, null);
                        }
                        
                        // 注入 Capacitor 环境检测增强脚本
                        String envScript = getCapacitorEnvScript();
                        if (envScript != null && !envScript.isEmpty()) {
                            view.evaluateJavascript(envScript, null);
                        }
                    }
                });
            }
        } catch (Exception e) {
            android.util.Log.e("MD6MainActivity", "注入 polyfill 失败", e);
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
            "      }, 100);" +
            "      setTimeout(function() { clearInterval(interval); }, 5000);" +
            "    }" +
            "  }" +
            "  " +
            "  waitForCapacitor(function() {" +
            "    console.log('[FS-Polyfill] Capacitor FSAccess 插件就绪，安装 File System Access API Polyfill');" +
            "    " +
            "    var FSAccess = window.Capacitor.Plugins.FSAccess;" +
            "    " +
            "    // URI 到 Handle 的映射" +
            "    var handleMap = new Map();" +
            "    " +
            "    // FileSystemFileHandle (SAF URI 版)" +
            "    function FSFileHandle(uri, name, size) {" +
            "      this.kind = 'file';" +
            "      this._uri = uri;" +
            "      this._name = name || 'unknown';" +
            "      this._size = size || 0;" +
            "      handleMap.set(uri, this);" +
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
            "      handleMap.set(uri, this);" +
            "    }" +
            "    FSDirHandle.prototype.getFileHandle = function(name, opts) {" +
            "      return Promise.reject(new DOMException('Not supported with SAF', 'NotSupportedError'));" +
            "    };" +
            "    FSDirHandle.prototype.getDirectoryHandle = function(name, opts) {" +
            "      return Promise.reject(new DOMException('Not supported with SAF', 'NotSupportedError'));" +
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
            "      return FSAccess.showDirectoryPicker({mode: mode}).then(function(r) {" +
            "        if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');" +
            "        return new FSDirHandle(r.uri, r.name);" +
            "      });" +
            "    };" +
            "    " +
            "    // showOpenFilePicker - 使用 SAF 原生文件选择器" +
            "    window.showOpenFilePicker = function(opts) {" +
            "      var multiple = (opts && opts.multiple) || false;" +
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
            "      return FSAccess.showSaveFilePicker({suggestedName: suggestedName}).then(function(r) {" +
            "        if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');" +
            "        return new FSFileHandle(r.uri, r.name);" +
            "      });" +
            "    };" +
            "    " +
            "    window.FileSystemFileHandle = FSFileHandle;" +
            "    window.FileSystemDirectoryHandle = FSDirHandle;" +
            "    " +
            "    // StorageManager.getDirectory polyfill - 返回上次选择的目录" +
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
            "    window.dispatchEvent(new CustomEvent('fs-polyfill-ready'));" +
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
            "  console.log('[MD6-Env] 移动端环境增强脚本已注入');" +
            "})();";
    }
}