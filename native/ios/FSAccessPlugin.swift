import UIKit
import Capacitor
import WebKit
import MobileCoreServices

/**
 * FSAccess 原生插件 (iOS)
 * 
 * 使用 UIDocumentPickerViewController 实现文件/目录选择器
 * - 选择目录: UIDocumentPickerViewController (mode: .open)
 * - 选择文件: UIDocumentPickerViewController (mode: .open)
 * - 保存文件: UIDocumentPickerViewController (mode: .export)
 * 
 * 通过 content URL 读写文件，持久化安全作用域访问
 */
@objc(FSAccessPlugin)
public class FSAccessPlugin: CAPPlugin {
    
    private static let TAG = "FSAccessPlugin"
    
    // 保存当前挂起的 Capacitor 调用
    private var savedCall: CAPPluginCall?
    
    // 持久化的 URL 访问权限
    private var persistedDirectoryURL: URL?
    
    // Picker 类型
    private enum PickerMode {
        case directory
        case openFile
        case saveFile
    }
    
    private var currentPickerMode: PickerMode = .directory
    
    // ============================================================
    // showDirectoryPicker
    // ============================================================
    
    @objc func showDirectoryPicker(_ call: CAPPluginCall) {
        savedCall = call
        currentPickerMode = .directory
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // iOS 14+ 支持选择目录
            if #available(iOS 14.0, *) {
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
                picker.delegate = self
                picker.allowsMultipleSelection = false
                self.presentViewController(picker)
            } else {
                // iOS 13 及以下回退 - 使用 Documents 目录
                let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                let result: [String: Any] = [
                    "uri": documentsDir?.absoluteString ?? "",
                    "name": "Documents",
                    "type": "directory",
                    "cancelled": false
                ]
                call.resolve(result)
            }
        }
    }
    
    // ============================================================
    // showOpenFilePicker
    // ============================================================
    
    @objc func showOpenFilePicker(_ call: CAPPluginCall) {
        savedCall = call
        currentPickerMode = .openFile
        
        let multiple = call.getBool("multiple") ?? false
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
            picker.delegate = self
            picker.allowsMultipleSelection = multiple
            self.presentViewController(picker)
        }
    }
    
    // ============================================================
    // showSaveFilePicker
    // ============================================================
    
    @objc func showSaveFilePicker(_ call: CAPPluginCall) {
        savedCall = call
        currentPickerMode = .saveFile
        
        let suggestedName = call.getString("suggestedName") ?? "untitled.txt"
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // UIDocumentPickerViewController for export
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent(suggestedName)
            
            // 创建临时文件以便导出
            if !FileManager.default.fileExists(atPath: tempFile.path) {
                FileManager.default.createFile(atPath: tempFile.path, contents: Data())
            }
            
            let picker = UIDocumentPickerViewController(forExporting: [tempFile], asCopy: true)
            picker.delegate = self
            self.presentViewController(picker)
        }
    }
    
    // ============================================================
    // readFile - 通过 URL 读取文件
    // ============================================================
    
    @objc func readFile(_ call: CAPPluginCall) {
        guard let uriString = call.getString("uri") else {
            call.reject("uri is required")
            return
        }
        
        guard let url = URL(string: uriString) else {
            call.reject("Invalid URI")
            return
        }
        
        // 访问安全作用域资源
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                url.stopAccessingSecurityScopedResource()
            }
        }
        
        do {
            let data = try Data(contentsOf: url)
            let base64 = data.base64EncodedString()
            
            let result: [String: Any] = [
                "data": base64,
                "size": data.count,
                "name": url.lastPathComponent
            ]
            call.resolve(result)
        } catch {
            print("[FSAccessPlugin] readFile failed: \(error)")
            call.reject("Failed to read file: \(error.localizedDescription)")
        }
    }
    
    // ============================================================
    // writeFile - 通过 URL 写入文件
    // ============================================================
    
    @objc func writeFile(_ call: CAPPluginCall) {
        guard let uriString = call.getString("uri"),
              let base64Data = call.getString("data") else {
            call.reject("uri and data are required")
            return
        }
        
        guard let url = URL(string: uriString),
              let data = Data(base64Encoded: base64Data) else {
            call.reject("Invalid URI or data")
            return
        }
        
        // 访问安全作用域资源
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                url.stopAccessingSecurityScopedResource()
            }
        }
        
        do {
            try data.write(to: url, options: .atomic)
            let result: [String: Any] = ["size": data.count]
            call.resolve(result)
        } catch {
            print("[FSAccessPlugin] writeFile failed: \(error)")
            call.reject("Failed to write file: \(error.localizedDescription)")
        }
    }
    
    // ============================================================
    // listDirectory - 列出目录内容
    // ============================================================
    
    @objc func listDirectory(_ call: CAPPluginCall) {
        guard let uriString = call.getString("uri") else {
            call.reject("uri is required")
            return
        }
        
        guard let url = URL(string: uriString) else {
            call.reject("Invalid URI")
            return
        }
        
        // 访问安全作用域资源
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                url.stopAccessingSecurityScopedResource()
            }
        }
        
        do {
            let contents = try FileManager.default.contentsOfDirectory(
                at: url,
                includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey, .nameKey],
                options: [.skipsHiddenFiles]
            )
            
            var entries: [[String: Any]] = []
            
            for itemURL in contents {
                let resourceValues = try itemURL.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .nameKey])
                let isDirectory = resourceValues.isDirectory ?? false
                let name = resourceValues.name ?? itemURL.lastPathComponent
                let size = resourceValues.fileSize ?? 0
                
                let entry: [String: Any] = [
                    "name": name,
                    "uri": itemURL.absoluteString,
                    "type": isDirectory ? "directory" : "file",
                    "size": size
                ]
                entries.append(entry)
            }
            
            call.resolve(["entries": entries])
        } catch {
            print("[FSAccessPlugin] listDirectory failed: \(error)")
            call.reject("Failed to list directory: \(error.localizedDescription)")
        }
    }
    
    // ============================================================
    // getPersistedUri - 获取持久化的目录 URL
    // ============================================================
    
    @objc func getPersistedUri(_ call: CAPPluginCall) {
        let uri = persistedDirectoryURL?.absoluteString
        call.resolve(["uri": uri as Any])
    }
    
    // ============================================================
    // requestAllPermissions - 请求所有权限
    // ============================================================
    
    @objc func requestAllPermissions(_ call: CAPPluginCall) {
        // iOS 不需要运行时权限请求来访问文档
        // UIDocumentPickerViewController 会自动处理权限
        call.resolve()
    }
    
    // ============================================================
    // 辅助方法
    // ============================================================
    
    private func presentViewController(_ viewController: UIViewController) {
        guard let rootVC = self.bridge?.viewController else {
            print("[FSAccessPlugin] 无法获取 rootViewController")
            savedCall?.reject("Cannot present picker")
            savedCall = nil
            return
        }
        
        rootVC.present(viewController, animated: true)
    }
}

// ============================================================
// UIDocumentPickerDelegate
// ============================================================

extension FSAccessPlugin: UIDocumentPickerDelegate {
    
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let call = savedCall else { return }
        
        switch currentPickerMode {
        case .directory:
            handleDirectoryPick(urls: urls, call: call)
        case .openFile:
            handleFilePick(urls: urls, call: call)
        case .saveFile:
            handleSavePick(urls: urls, call: call)
        }
        
        savedCall = nil
    }
    
    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        savedCall?.resolve(["cancelled": true])
        savedCall = nil
    }
    
    private func handleDirectoryPick(urls: [URL], call: CAPPluginCall) {
        guard let url = urls.first else {
            call.resolve(["cancelled": true])
            return
        }
        
        // 持久化安全作用域访问
        let accessing = url.startAccessingSecurityScopedResource()
        persistedDirectoryURL = url
        
        // 保存 bookmark 以便后续恢复访问
        do {
            let bookmark = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(bookmark, forKey: "fs_persisted_dir_bookmark")
        } catch {
            print("[FSAccessPlugin] 创建 bookmark 失败: \(error)")
        }
        
        let result: [String: Any] = [
            "uri": url.absoluteString,
            "name": url.lastPathComponent.isEmpty ? "Directory" : url.lastPathComponent,
            "type": "directory",
            "cancelled": false
        ]
        call.resolve(result)
    }
    
    private func handleFilePick(urls: [URL], call: CAPPluginCall) {
        var files: [[String: Any]] = []
        
        for url in urls {
            let accessing = url.startAccessingSecurityScopedResource()
            
            // 获取文件大小
            var fileSize: Int = 0
            if let resourceValues = try? url.resourceValues(forKeys: [.fileSizeKey]) {
                fileSize = resourceValues.fileSize ?? 0
            }
            
            let file: [String: Any] = [
                "uri": url.absoluteString,
                "name": url.lastPathComponent,
                "size": fileSize,
                "type": "file"
            ]
            files.append(file)
            
            // 注意：不在这里停止访问，让 JS 端后续读取
        }
        
        call.resolve([
            "files": files,
            "cancelled": false
        ])
    }
    
    private func handleSavePick(urls: [URL], call: CAPPluginCall) {
        guard let url = urls.first else {
            call.resolve(["cancelled": true])
            return
        }
        
        let result: [String: Any] = [
            "uri": url.absoluteString,
            "name": url.lastPathComponent,
            "type": "file",
            "cancelled": false
        ]
        call.resolve(result)
    }
}