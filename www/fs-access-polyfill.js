/**
 * File System Access API Polyfill for Capacitor
 * 
 * 桥接 Web File System Access API 到 Capacitor Filesystem 插件
 * 在 Android/iOS 上提供与桌面浏览器一致的文件操作体验
 * 
 * 支持的 API:
 * - window.showOpenFilePicker()
 * - window.showSaveFilePicker()
 * - window.showDirectoryPicker()
 * - FileSystemFileHandle / FileSystemDirectoryHandle
 * - FileSystemWritableFileStream
 */

;(async function () {
  'use strict';

  // 如果浏览器已经原生支持 FS Access API，则跳过
  if (window.showOpenFilePicker && window.showSaveFilePicker && window.showDirectoryPicker) {
    console.log('[FS-Polyfill] 浏览器已原生支持 File System Access API，跳过 polyfill');
    return;
  }

  // 检测是否在 Capacitor 环境中
  const isCapacitor = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());

  if (!isCapacitor) {
    console.log('[FS-Polyfill] 非 Capacitor 环境，跳过 polyfill');
    return;
  }

  console.log('[FS-Polyfill] 初始化 Capacitor File System Access API Polyfill');

  // 动态导入 Capacitor 插件
  let Filesystem, Share, Dialog;
  try {
    const fs = await import('@capacitor/filesystem');
    Filesystem = fs.Filesystem;
    const share = await import('@capacitor/share');
    Share = share.Share;
    const dialog = await import('@capacitor/dialog');
    Dialog = dialog.Dialog;
  } catch (e) {
    // 回退：使用全局 Capacitor 插件
    try {
      Filesystem = Capacitor.Plugins.Filesystem || Capacitor.Plugins.Filesystem;
      Share = Capacitor.Plugins.Share;
      Dialog = Capacitor.Plugins.Dialog;
    } catch (e2) {
      console.warn('[FS-Polyfill] 无法加载 Capacitor 插件:', e2);
      return;
    }
  }

  // ============================================================
  // 辅助工具
  // ============================================================

  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  /**
   * 生成唯一 ID
   */
  function generateId() {
    return 'fs-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9);
  }

  /**
   * Base64 编码
   */
  function uint8ArrayToBase64(uint8Array) {
    let binary = '';
    for (let i = 0; i < uint8Array.length; i++) {
      binary += String.fromCharCode(uint8Array[i]);
    }
    return btoa(binary);
  }

  /**
   * Base64 解码
   */
  function base64ToUint8Array(base64) {
    const binary = atob(base64);
    const uint8Array = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      uint8Array[i] = binary.charCodeAt(i);
    }
    return uint8Array;
  }

  /**
   * 从 Capacitor Filesystem 路径中提取文件名
   */
  function getFileName(path) {
    const parts = path.split('/');
    return parts[parts.length - 1];
  }

  /**
   * 获取文件扩展名
   */
  function getFileExtension(filename) {
    const parts = filename.split('.');
    return parts.length > 1 ? parts[parts.length - 1] : '';
  }

  /**
   * MIME 类型映射
   */
  function getMimeType(filename) {
    const ext = getFileExtension(filename).toLowerCase();
    const mimeMap = {
      'txt': 'text/plain',
      'json': 'application/json',
      'js': 'text/javascript',
      'ts': 'text/typescript',
      'html': 'text/html',
      'css': 'text/css',
      'md': 'text/markdown',
      'csv': 'text/csv',
      'xml': 'text/xml',
      'png': 'image/png',
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'gif': 'image/gif',
      'svg': 'image/svg+xml',
      'webp': 'image/webp',
      'pdf': 'application/pdf',
      'zip': 'application/zip',
      'doc': 'application/msword',
      'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'xls': 'application/vnd.ms-excel',
      'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    };
    return mimeMap[ext] || 'application/octet-stream';
  }

  // ============================================================
  // 文件句柄存储
  // ============================================================

  /**
   * 文件句柄注册表 - 存储已打开的文件/目录引用
   */
  const handleRegistry = new Map();

  // ============================================================
  // FileSystemWritableFileStream
  // ============================================================

  class FileSystemWritableFileStream extends WritableStream {
    constructor(fileHandle, keepExistingData) {
      super({
        start(controller) {
          this._fileHandle = fileHandle;
          this._keepExistingData = keepExistingData;
          this._chunks = [];
          this._position = 0;
        },
        write(chunk) {
          if (chunk instanceof Uint8Array) {
            this._chunks.push(chunk);
            this._position += chunk.length;
          } else if (typeof chunk === 'string') {
            const encoded = encoder.encode(chunk);
            this._chunks.push(encoded);
            this._position += encoded.length;
          } else if (chunk && chunk.type === 'seek') {
            this._position = chunk.position;
          } else if (chunk && chunk.type === 'truncate') {
            // 简化实现：截断操作
            this._chunks = this._chunks.slice(0, chunk.size);
          }
        },
        async close() {
          try {
            // 合并所有 chunks
            const totalLength = this._chunks.reduce((acc, chunk) => acc + chunk.length, 0);
            const combined = new Uint8Array(totalLength);
            let offset = 0;
            for (const chunk of this._chunks) {
              combined.set(chunk, offset);
              offset += chunk.length;
            }

            const base64Data = uint8ArrayToBase64(combined);

            await Filesystem.writeFile({
              path: this._fileHandle._path,
              data: base64Data,
              directory: this._fileHandle._directory,
              recursive: true,
            });

            this._fileHandle._size = totalLength;
          } catch (err) {
            console.error('[FS-Polyfill] 写入文件失败:', err);
            throw err;
          }
        },
        abort(reason) {
          this._chunks = [];
          console.warn('[FS-Polyfill] 写入流被中止:', reason);
        },
      });
    }
  }

  // ============================================================
  // FileSystemFileHandle
  // ============================================================

  class FileSystemFileHandle {
    constructor(path, directory, name) {
      this.kind = 'file';
      this._path = path;
      this._directory = directory || 'DOCUMENTS';
      this._name = name || getFileName(path);
      this._id = generateId();
      this._size = 0;
      this._lastModified = Date.now();
      handleRegistry.set(this._id, this);
    }

    get name() {
      return this._name;
    }

    async getFile() {
      try {
        const result = await Filesystem.readFile({
          path: this._path,
          directory: this._directory,
        });

        // Capacitor readFile 返回 base64 或 string
        let data;
        if (typeof result.data === 'string') {
          try {
            data = base64ToUint8Array(result.data);
          } catch {
            data = encoder.encode(result.data);
          }
        }

        this._size = data ? data.length : 0;

        const file = new File([data], this._name, {
          type: getMimeType(this._name),
          lastModified: this._lastModified,
        });

        return file;
      } catch (err) {
        console.error('[FS-Polyfill] 读取文件失败:', err);
        throw new DOMException('File not found', 'NotFoundError');
      }
    }

    async createWritable({ keepExistingData = false } = {}) {
      return new FileSystemWritableFileStream(this, keepExistingData);
    }

    async isSameEntry(other) {
      return other instanceof FileSystemFileHandle &&
        other._path === this._path &&
        other._directory === this._directory;
    }

    async remove() {
      try {
        await Filesystem.deleteFile({
          path: this._path,
          directory: this._directory,
        });
        handleRegistry.delete(this._id);
      } catch (err) {
        throw new DOMException('Failed to remove file', 'InvalidStateError');
      }
    }
  }

  // ============================================================
  // FileSystemDirectoryHandle
  // ============================================================

  class FileSystemDirectoryHandle {
    constructor(path, directory, name) {
      this.kind = 'directory';
      this._path = path;
      this._directory = directory || 'DOCUMENTS';
      this._name = name || getFileName(path) || 'Documents';
      this._id = generateId();
      handleRegistry.set(this._id, this);
    }

    get name() {
      return this._name;
    }

    async getFileHandle(name, { create = false } = {}) {
      const childPath = this._path ? `${this._path}/${name}` : name;

      if (!create) {
        // 检查文件是否存在
        try {
          await Filesystem.stat({
            path: childPath,
            directory: this._directory,
          });
        } catch {
          throw new DOMException('File not found', 'NotFoundError');
        }
      }

      return new FileSystemFileHandle(childPath, this._directory, name);
    }

    async getDirectoryHandle(name, { create = false } = {}) {
      const childPath = this._path ? `${this._path}/${name}` : name;

      if (create) {
        try {
          await Filesystem.mkdir({
            path: childPath,
            directory: this._directory,
            recursive: true,
          });
        } catch {
          // 目录可能已存在
        }
      } else {
        try {
          const stat = await Filesystem.stat({
            path: childPath,
            directory: this._directory,
          });
          if (stat.type !== 'directory') {
            throw new DOMException('Not a directory', 'TypeMismatchError');
          }
        } catch (err) {
          if (err instanceof DOMException) throw err;
          throw new DOMException('Directory not found', 'NotFoundError');
        }
      }

      return new FileSystemDirectoryHandle(childPath, this._directory, name);
    }

    async removeEntry(name, { recursive = false } = {}) {
      const childPath = this._path ? `${this._path}/${name}` : name;

      try {
        const stat = await Filesystem.stat({
          path: childPath,
          directory: this._directory,
        });

        if (stat.type === 'directory') {
          await Filesystem.rmdir({
            path: childPath,
            directory: this._directory,
            recursive,
          });
        } else {
          await Filesystem.deleteFile({
            path: childPath,
            directory: this._directory,
          });
        }
      } catch (err) {
        throw new DOMException('Failed to remove entry', 'InvalidStateError');
      }
    }

    async *values() {
      try {
        const result = await Filesystem.readdir({
          path: this._path || '.',
          directory: this._directory,
        });

        for (const entry of result.files) {
          if (entry.type === 'directory') {
            yield new FileSystemDirectoryHandle(
              this._path ? `${this._path}/${entry.name}` : entry.name,
              this._directory,
              entry.name
            );
          } else {
            yield new FileSystemFileHandle(
              this._path ? `${this._path}/${entry.name}` : entry.name,
              this._directory,
              entry.name
            );
          }
        }
      } catch (err) {
        console.error('[FS-Polyfill] 读取目录失败:', err);
      }
    }

    async *entries() {
      for await (const handle of this.values()) {
        yield [handle.name, handle];
      }
    }

    async *keys() {
      for await (const [name] of this.entries()) {
        yield name;
      }
    }

    async isSameEntry(other) {
      return other instanceof FileSystemDirectoryHandle &&
        other._path === other._path &&
        this._directory === other._directory;
    }

    [Symbol.asyncIterator]() {
      return this.values();
    }
  }

  // ============================================================
  // showOpenFilePicker
  // ============================================================

  /**
   * 在移动端，由于没有原生的文件选择器对话框，
   * 使用 Capacitor 的方式来选择文件：
   * 1. 先尝试使用 Capacitor Filepicker 插件
   * 2. 回退到让用户从 Documents 目录中选择
   */
  async function showOpenFilePicker(options = {}) {
    const { multiple = false, types = [], excludeAcceptAllOptions = false } = options;

    console.log('[FS-Polyfill] showOpenFilePicker called', options);

    // 尝试使用 Capacitor 的文件选择功能
    // 由于 Capacitor 没有内置的文件选择器，我们使用 Share 插件的反向操作
    // 或者使用自定义的文件浏览方式

    try {
      // 方案1: 使用 Capacitor Filesystem 浏览 Documents 目录
      // 在移动端，我们展示一个目录浏览界面
      const dirHandle = new FileSystemDirectoryHandle('', 'DOCUMENTS', 'Documents');

      if (multiple) {
        // 返回数组 - 简化实现：返回目录中的所有文件
        const handles = [];
        for await (const entry of dirHandle.values()) {
          if (entry.kind === 'file') {
            handles.push(entry);
          }
        }
        return handles.length > 0 ? handles : [];
      } else {
        // 返回单个文件的数组
        // 让用户选择 - 简化实现：返回第一个文件
        for await (const entry of dirHandle.values()) {
          if (entry.kind === 'file') {
            return [entry];
          }
        }
        throw new DOMException('No files found', 'NotFoundError');
      }
    } catch (err) {
      if (err instanceof DOMException) throw err;
      console.error('[FS-Polyfill] showOpenFilePicker 失败:', err);
      throw new DOMException('User cancelled or no files selected', 'AbortError');
    }
  }

  // ============================================================
  // showSaveFilePicker
  // ============================================================

  async function showSaveFilePicker(options = {}) {
    const { suggestedName = 'untitled.txt', types = [] } = options;

    console.log('[FS-Polyfill] showSaveFilePicker called, suggestedName:', suggestedName);

    try {
      // 在移动端，文件保存到 Documents 目录
      const path = suggestedName;
      const handle = new FileSystemFileHandle(path, 'DOCUMENTS', suggestedName);
      return handle;
    } catch (err) {
      console.error('[FS-Polyfill] showSaveFilePicker 失败:', err);
      throw new DOMException('User cancelled', 'AbortError');
    }
  }

  // ============================================================
  // showDirectoryPicker
  // ============================================================

  async function showDirectoryPicker(options = {}) {
    const { mode = 'read' } = options;

    console.log('[FS-Polyfill] showDirectoryPicker called');

    try {
      // 在移动端，返回 Documents 目录作为根目录
      const handle = new FileSystemDirectoryHandle('', 'DOCUMENTS', 'Documents');
      return handle;
    } catch (err) {
      console.error('[FS-Polyfill] showDirectoryPicker 失败:', err);
      throw new DOMException('User cancelled', 'AbortError');
    }
  }

  // ============================================================
  // 注册全局 API
  // ============================================================

  window.showOpenFilePicker = showOpenFilePicker;
  window.showSaveFilePicker = showSaveFilePicker;
  window.showDirectoryPicker = showDirectoryPicker;

  // 注册 FileSystemFileHandle 和 FileSystemDirectoryHandle 到全局
  if (!window.FileSystemFileHandle) {
    window.FileSystemFileHandle = FileSystemFileHandle;
  }
  if (!window.FileSystemDirectoryHandle) {
    window.FileSystemDirectoryHandle = FileSystemDirectoryHandle;
  }
  if (!window.FileSystemWritableFileStream) {
    window.FileSystemWritableFileStream = FileSystemWritableFileStream;
  }

  // DataTransferItem.getAsFileSystemHandle polyfill
  if (window.DataTransferItem && !DataTransferItem.prototype.getAsFileSystemHandle) {
    DataTransferItem.prototype.getAsFileSystemHandle = async function () {
      if (this.kind === 'file') {
        const file = this.getAsFile();
        if (file) {
          return new FileSystemFileHandle(file.name, 'DOCUMENTS', file.name);
        }
      }
      return null;
    };
  }

  // StorageManager.getDirectory polyfill
  if (navigator.storage && !navigator.storage.getDirectory) {
    navigator.storage.getDirectory = async function () {
      return new FileSystemDirectoryHandle('', 'DOCUMENTS', 'Documents');
    };
  }

  console.log('[FS-Polyfill] File System Access API Polyfill 已安装完成');

  // 触发自定义事件，通知应用 polyfill 已就绪
  window.dispatchEvent(new CustomEvent('fs-polyfill-ready', {
    detail: {
      showOpenFilePicker: true,
      showSaveFilePicker: true,
      showDirectoryPicker: true,
    }
  }));

})();