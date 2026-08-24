import UIKit
import Capacitor
import WebKit

/**
 * MD6 App AppDelegate
 * 
 * 扩展 Capacitor AppDelegate，注入 File System Access API Polyfill
 * 使远程网页在 iOS WKWebView 中支持 FS Access API
 */
@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Override point for customization after application launch.
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
        // Called when the app was opened with a url. Feel free to add additional processing here.
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }
}

// ============================================================
// WebView 注入扩展
// ============================================================

/**
 * CapacitorBridge 扩展：注入 FS Access API Polyfill
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

    /// FS Access API Polyfill JavaScript 代码
    private func getFileSystemPolyfillScript() -> String {
        return """
        (function() {
          // 检测是否已注入
          if (window.__fsPolyfillInjected) return;
          window.__fsPolyfillInjected = true;

          // 等待 Capacitor 就绪
          function waitForCapacitor(callback) {
            if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem) {
              callback();
            } else {
              var interval = setInterval(function() {
                if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem) {
                  clearInterval(interval);
                  callback();
                }
              }, 100);
              // 超时 5 秒后放弃
              setTimeout(function() { clearInterval(interval); }, 5000);
            }
          }

          waitForCapacitor(function() {
            console.log('[FS-Polyfill] Capacitor 就绪，安装 File System Access API Polyfill (iOS)');

            var Filesystem = window.Capacitor.Plugins.Filesystem;

            // 如果浏览器已原生支持，跳过
            if (window.showOpenFilePicker && window.showSaveFilePicker && window.showDirectoryPicker) {
              console.log('[FS-Polyfill] 浏览器已原生支持 FS Access API');
              return;
            }

            // 简化的 Base64 编解码
            function uint8ToBase64(u8) {
              var b = '';
              for (var i = 0; i < u8.length; i++) b += String.fromCharCode(u8[i]);
              return btoa(b);
            }
            function base64ToUint8(b64) {
              var b = atob(b64);
              var u8 = new Uint8Array(b.length);
              for (var i = 0; i < b.length; i++) u8[i] = b.charCodeAt(i);
              return u8;
            }

            // FileSystemFileHandle
            function FSFileHandle(path, dir, name) {
              this.kind = 'file';
              this._path = path;
              this._dir = dir || 'DOCUMENTS';
              this._name = name || path.split('/').pop();
            }
            FSFileHandle.prototype.getFile = function() {
              return Filesystem.readFile({path: this._path, directory: this._dir}).then(function(r) {
                var data = base64ToUint8(r.data);
                return new File([data], this._name, {type: 'application/octet-stream'});
              }.bind(this));
            };
            FSFileHandle.prototype.createWritable = function() {
              var self = this;
              return Promise.resolve({
                write: function(data) { this._data = data; }.bind(this),
                close: function() {
                  var d = this._data;
                  if (typeof d === 'string') d = new TextEncoder().encode(d);
                  return Filesystem.writeFile({path: self._path, data: uint8ToBase64(d), directory: self._dir, recursive: true});
                }.bind(this)
              });
            };
            FSFileHandle.prototype.isSameEntry = function(other) {
              return other instanceof FSFileHandle && other._path === this._path;
            };

            // FileSystemDirectoryHandle
            function FSDirHandle(path, dir, name) {
              this.kind = 'directory';
              this._path = path;
              this._dir = dir || 'DOCUMENTS';
              this._name = name || 'Documents';
            }
            FSDirHandle.prototype.getFileHandle = function(name, opts) {
              var p = this._path ? this._path + '/' + name : name;
              return Promise.resolve(new FSFileHandle(p, this._dir, name));
            };
            FSDirHandle.prototype.getDirectoryHandle = function(name, opts) {
              var p = this._path ? this._path + '/' + name : name;
              if (opts && opts.create) {
                return Filesystem.mkdir({path: p, directory: this._dir, recursive: true}).then(function() {
                  return new FSDirHandle(p, this._dir, name);
                }.bind(this), function() { return new FSDirHandle(p, this._dir, name); });
              }
              return Promise.resolve(new FSDirHandle(p, this._dir, name));
            };
            FSDirHandle.prototype.removeEntry = function(name, opts) {
              var p = this._path ? this._path + '/' + name : name;
              return Filesystem.deleteFile({path: p, directory: this._dir}).catch(function() {
                return Filesystem.rmdir({path: p, directory: this._dir, recursive: !!(opts && opts.recursive)});
              });
            };
            FSDirHandle.prototype.values = function() {
              var self = this;
              var entries = [];
              var index = 0;
              return {
                next: function() {
                  if (entries.length === 0 && index === 0) {
                    return Filesystem.readdir({path: self._path || '.', directory: self._dir}).then(function(r) {
                      entries = r.files.map(function(f) {
                        var p = self._path ? self._path + '/' + f.name : f.name;
                        return f.type === 'directory' ? new FSDirHandle(p, self._dir, f.name) : new FSFileHandle(p, self._dir, f.name);
                      });
                      index = 0;
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
              return other instanceof FSDirHandle && other._path === this._path;
            };

            // 注册全局 API
            window.showOpenFilePicker = function(opts) {
              return Filesystem.readdir({path: '.', directory: 'DOCUMENTS'}).then(function(r) {
                var files = r.files.filter(function(f) { return f.type !== 'directory'; });
                if (files.length === 0) return [];
                return [new FSFileHandle(files[0].name, 'DOCUMENTS', files[0].name)];
              });
            };
            window.showSaveFilePicker = function(opts) {
              var name = (opts && opts.suggestedName) || 'untitled.txt';
              return Promise.resolve(new FSFileHandle(name, 'DOCUMENTS', name));
            };
            window.showDirectoryPicker = function() {
              return Promise.resolve(new FSDirHandle('', 'DOCUMENTS', 'Documents'));
            };
            window.FileSystemFileHandle = FSFileHandle;
            window.FileSystemDirectoryHandle = FSDirHandle;

            // StorageManager.getDirectory polyfill
            if (navigator.storage && !navigator.storage.getDirectory) {
              navigator.storage.getDirectory = function() {
                return Promise.resolve(new FSDirHandle('', 'DOCUMENTS', 'Documents'));
              };
            }

            console.log('[FS-Polyfill] File System Access API Polyfill 安装完成 (iOS)');
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