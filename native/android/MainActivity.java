package com.md6.app;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import com.getcapacitor.BridgeActivity;

/**
 * MD6 App 主 Activity
 * 
 * 扩展 Capacitor BridgeActivity，注入 File System Access API Polyfill
 * 使远程网页在 Android WebView 中支持 FS Access API
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        injectFileSystemPolyfill();
    }

    /**
     * 注入 File System Access API Polyfill 到 WebView
     * 
     * 在页面加载完成后注入 polyfill JavaScript，
     * 使远程网页能够使用 FS Access API（通过 Capacitor Filesystem 插件）
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
                        
                        // 注入 FS Access API Polyfill
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
     * 这个脚本在 WebView 中运行，将 Capacitor Filesystem 插件
     * 桥接为标准的 File System Access API
     */
    private String getFileSystemPolyfillScript() {
        return "(function() {" +
            "  // 检测是否已注入" +
            "  if (window.__fsPolyfillInjected) return;" +
            "  window.__fsPolyfillInjected = true;" +
            "  " +
            "  // 等待 Capacitor 就绪" +
            "  function waitForCapacitor(callback) {" +
            "    if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem) {" +
            "      callback();" +
            "    } else {" +
            "      var interval = setInterval(function() {" +
            "        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem) {" +
            "          clearInterval(interval);" +
            "          callback();" +
            "        }" +
            "      }, 100);" +
            "      // 超时 5 秒后放弃" +
            "      setTimeout(function() { clearInterval(interval); }, 5000);" +
            "    }" +
            "  }" +
            "  " +
            "  waitForCapacitor(function() {" +
            "    console.log('[FS-Polyfill] Capacitor 就绪，安装 File System Access API Polyfill');" +
            "    " +
            "    var Filesystem = window.Capacitor.Plugins.Filesystem;" +
            "    " +
            "    // 如果浏览器已原生支持，跳过" +
            "    if (window.showOpenFilePicker && window.showSaveFilePicker && window.showDirectoryPicker) {" +
            "      console.log('[FS-Polyfill] 浏览器已原生支持 FS Access API');" +
            "      return;" +
            "    }" +
            "    " +
            "    // 简化的 Base64 编解码" +
            "    function uint8ToBase64(u8) {" +
            "      var b = '';" +
            "      for (var i = 0; i < u8.length; i++) b += String.fromCharCode(u8[i]);" +
            "      return btoa(b);" +
            "    }" +
            "    function base64ToUint8(b64) {" +
            "      var b = atob(b64);" +
            "      var u8 = new Uint8Array(b.length);" +
            "      for (var i = 0; i < b.length; i++) u8[i] = b.charCodeAt(i);" +
            "      return u8;" +
            "    }" +
            "    " +
            "    // FileSystemFileHandle" +
            "    function FSFileHandle(path, dir, name) {" +
            "      this.kind = 'file';" +
            "      this._path = path;" +
            "      this._dir = dir || 'DOCUMENTS';" +
            "      this._name = name || path.split('/').pop();" +
            "    }" +
            "    FSFileHandle.prototype.getFile = function() {" +
            "      return Filesystem.readFile({path: this._path, directory: this._dir}).then(function(r) {" +
            "        var data = base64ToUint8(r.data);" +
            "        return new File([data], this._name, {type: 'application/octet-stream'});" +
            "      }.bind(this));" +
            "    };" +
            "    FSFileHandle.prototype.createWritable = function() {" +
            "      var self = this;" +
            "      return Promise.resolve({" +
            "        write: function(data) { this._data = data; }.bind(this)," +
            "        close: function() {" +
            "          var d = this._data;" +
            "          if (typeof d === 'string') d = new TextEncoder().encode(d);" +
            "          return Filesystem.writeFile({path: self._path, data: uint8ToBase64(d), directory: self._dir, recursive: true});" +
            "        }.bind(this)" +
            "      });" +
            "    };" +
            "    FSFileHandle.prototype.isSameEntry = function(other) {" +
            "      return other instanceof FSFileHandle && other._path === this._path;" +
            "    };" +
            "    " +
            "    // FileSystemDirectoryHandle" +
            "    function FSDirHandle(path, dir, name) {" +
            "      this.kind = 'directory';" +
            "      this._path = path;" +
            "      this._dir = dir || 'DOCUMENTS';" +
            "      this._name = name || 'Documents';" +
            "    }" +
            "    FSDirHandle.prototype.getFileHandle = function(name, opts) {" +
            "      var p = this._path ? this._path + '/' + name : name;" +
            "      return Promise.resolve(new FSFileHandle(p, this._dir, name));" +
            "    };" +
            "    FSDirHandle.prototype.getDirectoryHandle = function(name, opts) {" +
            "      var p = this._path ? this._path + '/' + name : name;" +
            "      if (opts && opts.create) {" +
            "        return Filesystem.mkdir({path: p, directory: this._dir, recursive: true}).then(function() {" +
            "          return new FSDirHandle(p, this._dir, name);" +
            "        }.bind(this), function() { return new FSDirHandle(p, this._dir, name); });" +
            "      }" +
            "      return Promise.resolve(new FSDirHandle(p, this._dir, name));" +
            "    };" +
            "    FSDirHandle.prototype.removeEntry = function(name, opts) {" +
            "      var p = this._path ? this._path + '/' + name : name;" +
            "      return Filesystem.deleteFile({path: p, directory: this._dir}).catch(function() {" +
            "        return Filesystem.rmdir({path: p, directory: this._dir, recursive: !!(opts && opts.recursive)});" +
            "      });" +
            "    };" +
            "    FSDirHandle.prototype.values = function() {" +
            "      var self = this;" +
            "      var entries = [];" +
            "      var index = 0;" +
            "      return {" +
            "        next: function() {" +
            "          if (entries.length === 0 && index === 0) {" +
            "            return Filesystem.readdir({path: self._path || '.', directory: self._dir}).then(function(r) {" +
            "              entries = r.files.map(function(f) {" +
            "                var p = self._path ? self._path + '/' + f.name : f.name;" +
            "                return f.type === 'directory' ? new FSDirHandle(p, self._dir, f.name) : new FSFileHandle(p, self._dir, f.name);" +
            "              });" +
            "              index = 0;" +
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
            "      return other instanceof FSDirHandle && other._path === this._path;" +
            "    };" +
            "    " +
            "    // 注册全局 API" +
            "    window.showOpenFilePicker = function(opts) {" +
            "      return Filesystem.readdir({path: '.', directory: 'DOCUMENTS'}).then(function(r) {" +
            "        var files = r.files.filter(function(f) { return f.type !== 'directory'; });" +
            "        if (files.length === 0) return [];" +
            "        return [new FSFileHandle(files[0].name, 'DOCUMENTS', files[0].name)];" +
            "      });" +
            "    };" +
            "    window.showSaveFilePicker = function(opts) {" +
            "      var name = (opts && opts.suggestedName) || 'untitled.txt';" +
            "      return Promise.resolve(new FSFileHandle(name, 'DOCUMENTS', name));" +
            "    };" +
            "    window.showDirectoryPicker = function() {" +
            "      return Promise.resolve(new FSDirHandle('', 'DOCUMENTS', 'Documents'));" +
            "    };" +
            "    window.FileSystemFileHandle = FSFileHandle;" +
            "    window.FileSystemDirectoryHandle = FSDirHandle;" +
            "    " +
            "    // StorageManager.getDirectory polyfill" +
            "    if (navigator.storage && !navigator.storage.getDirectory) {" +
            "      navigator.storage.getDirectory = function() {" +
            "        return Promise.resolve(new FSDirHandle('', 'DOCUMENTS', 'Documents'));" +
            "      };" +
            "    }" +
            "    " +
            "    console.log('[FS-Polyfill] File System Access API Polyfill 安装完成');" +
            "    window.dispatchEvent(new CustomEvent('fs-polyfill-ready'));" +
            "  });" +
            "})();";
    }

    /**
     * 获取 Capacitor 环境增强脚本
     * 
     * 添加移动端特定的环境变量和功能检测
     */
    private String getCapacitorEnvScript() {
        return "(function() {" +
            "  if (window.__envScriptInjected) return;" +
            "  window.__envScriptInjected = true;" +
            "  " +
            "  // 标记为移动端环境" +
            "  window.__MD6_MOBILE__ = true;" +
            "  window.__MD6_PLATFORM__ = 'android';" +
            "  " +
            "  // 增强 navigator.permissions API" +
            "  if (!navigator.permissions) {" +
            "    navigator.permissions = {" +
            "      query: function(desc) {" +
            "        return Promise.resolve({state: 'granted', onchange: null});" +
            "      }" +
            "    };" +
            "  }" +
            "  " +
            "  // 确保 window.origin 可用" +
            "  if (!window.origin) {" +
            "    window.origin = window.location.origin || 'https://md6.pnt.pp.ua';" +
            "  }" +
            "  " +
            "  console.log('[MD6-Env] 移动端环境增强脚本已注入');" +
            "})();";
    }
}