import UIKit
import Capacitor
import WebKit
import UserNotifications

/**
 * MD6 App AppDelegate
 * 
 * 扩展 Capacitor AppDelegate：
 * 1. 注册 FSAccess 原生插件（UIDocumentPickerViewController）
 * 2. 注入 FS Access API Polyfill 到 WKWebView
 * 3. 请求通知权限
 */
@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // 请求通知权限
        requestNotificationPermissions()
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state.
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Use this method to release shared resources, save user data, invalidate timers.
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state.
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive.
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate.
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

    // ============================================================
    // 通知权限请求
    // ============================================================

    private func requestNotificationPermissions() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if granted {
                print("[MD6-iOS] 通知权限已授予")
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()
                }
            } else {
                print("[MD6-iOS] 通知权限被拒绝")
            }
            if let error = error {
                print("[MD6-iOS] 通知权限请求错误: \(error)")
            }
        }
    }
}

// ============================================================
// WebView 注入扩展
// ============================================================

/**
 * CAPBridgeViewController 扩展：注入 FS Access API Polyfill
 * 
 * 使用 WKUserScript 在页面加载前注入 JavaScript，
 * 确保远程网页可以使用 File System Access API
 */
extension CAPBridgeViewController {

    /// 在 viewDidLoad 后自动调用，设置 WebView 注入
    func setupFileSystemPolyfill() {
        guard let webView = self.webView else {
            print("[MD6-iOS] WebView 未就绪，延迟注入")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.setupFileSystemPolyfill()
            }
            return
        }

        // 注入 FS Access API Polyfill 脚本
        let polyfillScript = getFileSystemPolyfillScript()
        let userScript = WKUserScript(
            source: polyfillScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        webView.configuration.userContentController.addUserScript(userScript)

        // 注入环境增强脚本
        let envScript = getCapacitorEnvScript()
        let envUserScript = WKUserScript(
            source: envScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        webView.configuration.userContentController.addUserScript(envUserScript)

        print("[MD6-iOS] File System Access API Polyfill 已注入 WebView")
    }

    /// FS Access API Polyfill JavaScript 代码 - 使用 FSAccess 原生插件
    private func getFileSystemPolyfillScript() -> String {
        return """
        (function() {
          // 检测是否已注入
          if (window.__fsPolyfillInjected) return;
          window.__fsPolyfillInjected = true;

          // 等待 Capacitor FSAccess 插件就绪
          function waitForCapacitor(callback) {
            if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FSAccess) {
              callback();
            } else {
              var interval = setInterval(function() {
                if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FSAccess) {
                  clearInterval(interval);
                  callback();
                }
              }, 100);
              setTimeout(function() { clearInterval(interval); }, 5000);
            }
          }

          waitForCapacitor(function() {
            console.log('[FS-Polyfill] Capacitor FSAccess 插件就绪 (iOS)，安装 File System Access API Polyfill');

            var FSAccess = window.Capacitor.Plugins.FSAccess;

            // URI 到 Handle 的映射
            var handleMap = new Map();

            // FileSystemFileHandle (SAF URI 版)
            function FSFileHandle(uri, name, size) {
              this.kind = 'file';
              this._uri = uri;
              this._name = name || 'unknown';
              this._size = size || 0;
              handleMap.set(uri, this);
            }
            FSFileHandle.prototype.getFile = function() {
              return FSAccess.readFile({uri: this._uri}).then(function(r) {
                var data = Uint8Array.from(atob(r.data), function(c) { return c.charCodeAt(0); });
                this._size = data.length;
                return new File([data], this._name, {type: 'application/octet-stream'});
              }.bind(this));
            };
            FSFileHandle.prototype.createWritable = function() {
              var self = this;
              var chunks = [];
              return Promise.resolve({
                write: function(data) {
                  if (typeof data === 'string') data = new TextEncoder().encode(data);
                  if (data instanceof Uint8Array) chunks.push(data);
                },
                close: function() {
                  var total = chunks.reduce(function(a, c) { return a + c.length; }, 0);
                  var combined = new Uint8Array(total);
                  var off = 0;
                  chunks.forEach(function(c) { combined.set(c, off); off += c.length; });
                  var b64 = btoa(String.fromCharCode.apply(null, combined));
                  return FSAccess.writeFile({uri: self._uri, data: b64});
                }
              });
            };
            FSFileHandle.prototype.isSameEntry = function(other) {
              return other instanceof FSFileHandle && other._uri === this._uri;
            };

            // FileSystemDirectoryHandle (SAF URI 版)
            function FSDirHandle(uri, name) {
              this.kind = 'directory';
              this._uri = uri;
              this._name = name || 'Directory';
              handleMap.set(uri, this);
            }
            FSDirHandle.prototype.getFileHandle = function(name, opts) {
              return FSAccess.listDirectory({uri: this._uri}).then(function(r) {
                var found = (r.entries || []).find(function(e) { return e.name === name && e.type === 'file'; });
                if (found) return new FSFileHandle(found.uri, found.name, found.size);
                throw new DOMException('File not found: ' + name, 'NotFoundError');
              });
            };
            FSDirHandle.prototype.getDirectoryHandle = function(name, opts) {
              return FSAccess.listDirectory({uri: this._uri}).then(function(r) {
                var found = (r.entries || []).find(function(e) { return e.name === name && e.type === 'directory'; });
                if (found) return new FSDirHandle(found.uri, found.name);
                throw new DOMException('Directory not found: ' + name, 'NotFoundError');
              });
            };
            FSDirHandle.prototype.removeEntry = function(name, opts) {
              return Promise.reject(new DOMException('Not supported with document picker', 'NotSupportedError'));
            };
            FSDirHandle.prototype.values = function() {
              var self = this;
              var entries = [];
              var index = 0;
              var loaded = false;
              return {
                next: function() {
                  if (!loaded) {
                    return FSAccess.listDirectory({uri: self._uri}).then(function(r) {
                      loaded = true;
                      var arr = r.entries || [];
                      entries = arr.map(function(e) {
                        return e.type === 'directory' ? new FSDirHandle(e.uri, e.name) : new FSFileHandle(e.uri, e.name, e.size);
                      });
                      if (entries.length > 0) return {value: entries[index++], done: false};
                      return {value: undefined, done: true};
                    });
                  }
                  if (index < entries.length) return Promise.resolve({value: entries[index++], done: false});
                  return Promise.resolve({value: undefined, done: true});
                },
                [Symbol.asyncIterator]: function() { return this; }
              };
            };
            FSDirHandle.prototype.isSameEntry = function(other) {
              return other instanceof FSDirHandle && other._uri === this._uri;
            };

            // showDirectoryPicker - 使用 UIDocumentPickerViewController
            window.showDirectoryPicker = function(opts) {
              var mode = (opts && opts.mode) || 'read';
              return FSAccess.showDirectoryPicker({mode: mode}).then(function(r) {
                if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');
                return new FSDirHandle(r.uri, r.name);
              });
            };

            // showOpenFilePicker - 使用 UIDocumentPickerViewController
            window.showOpenFilePicker = function(opts) {
              var multiple = (opts && opts.multiple) || false;
              return FSAccess.showOpenFilePicker({multiple: multiple}).then(function(r) {
                if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');
                var files = (r.files || []).map(function(f) {
                  return new FSFileHandle(f.uri, f.name, f.size);
                });
                return files;
              });
            };

            // showSaveFilePicker - 使用 UIDocumentPickerViewController
            window.showSaveFilePicker = function(opts) {
              var suggestedName = (opts && opts.suggestedName) || 'untitled.txt';
              return FSAccess.showSaveFilePicker({suggestedName: suggestedName}).then(function(r) {
                if (r.cancelled) throw new DOMException('User cancelled', 'AbortError');
                return new FSFileHandle(r.uri, r.name);
              });
            };

            window.FileSystemFileHandle = FSFileHandle;
            window.FileSystemDirectoryHandle = FSDirHandle;

            // StorageManager.getDirectory polyfill
            if (navigator.storage && !navigator.storage.getDirectory) {
              navigator.storage.getDirectory = function() {
                return FSAccess.getPersistedUri().then(function(r) {
                  if (r.uri) return new FSDirHandle(r.uri, 'Persisted Directory');
                  throw new DOMException('No persisted directory', 'NotFoundError');
                });
              };
            }

            console.log('[FS-Polyfill] File System Access API Polyfill (iOS DocumentPicker) 安装完成');
            window.dispatchEvent(new CustomEvent('fs-polyfill-ready'));
          });
        })();
        """
    }

    /// Capacitor 环境增强脚本
    private func getCapacitorEnvScript() -> String {
        return """
        (function() {
          if (window.__envScriptInjected) return;
          window.__envScriptInjected = true;

          // 标记为移动端环境
          window.__MD6_MOBILE__ = true;
          window.__MD6_PLATFORM__ = 'ios';

          // 增强 navigator.permissions API
          if (!navigator.permissions) {
            navigator.permissions = {
              query: function(desc) {
                return Promise.resolve({state: 'granted', onchange: null});
              }
            };
          }

          // 确保 window.origin 可用
          if (!window.origin) {
            window.origin = window.location.origin || 'https://md6.pnt.pp.ua';
          }

          console.log('[MD6-Env] iOS 移动端环境增强脚本已注入');
        })();
        """
    }
}