/**
 * File System Access API Polyfill for Capacitor
 * 
 * 优先使用 FSAccess 原生插件（Android SAF / iOS UIDocumentPicker）
 * 回退到 Capacitor Filesystem 插件（仅限 Documents 目录操作）
 * 
 * 支持的 API:
 * - window.showOpenFilePicker()  → SAF 文件选择器
 * - window.showSaveFilePicker()  → SAF 保存选择器
 * - window.showDirectoryPicker() → SAF 目录选择器
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

  // ============================================================
  // 插件加载 - 优先 FSAccess 原生插件，回退 Filesystem
  // ============================================================

  let FSAccess = null;
  let Filesystem = null;

  // 等待 Capacitor 插件就绪
  async function waitForPlugins() {
    const maxWait = 5000;
    const interval = 100;
    let elapsed = 0;

    while (elapsed < maxWait) {
      if (window.Capacitor && window.Capacitor.Plugins) {
        FSAccess = window.Capacitor.Plugins.FSAccess || null;
        Filesystem = window.Capacitor.Plugins.Filesystem || null;
        if (FSAccess || Filesystem) return;
      }
      await new Promise(r => setTimeout(r, interval));
      elapsed += interval;
    }
    console.warn('[FS-Polyfill] 等待 Capacitor 插件超时');
  }

  await waitForPlugins();

  const useNativePicker = !!FSAccess;
  console.log('[FS-Polyfill] 使用原生选择器:', useNativePicker ? 'FSAccess (SAF)' : '回退 Filesystem');

  // 动态导入 Capacitor Filesystem（回退方案）
  if (!Filesystem) {
    try {
      const fs = await import('@capacitor/filesystem');
      Filesystem = fs.Filesystem;
    } catch (e) {
      try {
        Filesystem = Capacitor.Plugins.Filesystem;
      } catch (e2) {
        console.warn('[FS-Polyfill] 无法加载 Capacitor Filesystem 插件:', e2);
      }
    }
  }

  // ============================================================
  // 辅助工具
  // ============================================================

  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  function uint8ArrayToBase64(uint8Array) {
    let binary = '';
    for (let i = 0; i < uint8Array.length; i++) {
      binary += String.fromCharCode(uint8Array[i]);
    }
    return btoa(binary);
  }

  function base64ToUint8Array(base64) {
    const binary = atob(base64);
    const uint8Array = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      uint8Array[i] = binary.charCodeAt(i);
    }
    return uint8Array;
  }

  // ============================================================
  // SAF 版本 - 使用 FSAccess 原生插件的 Handle
  // ============================================================

  if (useNativePicker) {

    // --- SAF FileSystemFileHandle ---
    class SAFFileHandle {
      constructor(uri, name, size) {
        this.kind = 'file';
        this._uri = uri;
        this._name = name || 'unknown';
        this._size = size || 0;
      }

      get name() { return this._name; }

      async getFile() {
        try {
          const result = await FSAccess.readFile({ uri: this._uri });
          const data = base64ToUint8Array(result.data);
          this._size = data.length;
          return new File([data], this._name, {
            type: 'application/octet-stream',
            lastModified: Date.now(),
          });
        } catch (err) {
          console.error('[FS-Polyfill] SAF readFile 失败:', err);
          throw new DOMException('File not found', 'NotFoundError');
        }
      }

      async createWritable({ keepExistingData = false } = {}) {
        const self = this;
        const chunks = [];
        return {
          write(data) {
            if (typeof data === 'string') data = encoder.encode(data);
            if (data instanceof Uint8Array) chunks.push(data);
          },
          async close() {
            const total = chunks.reduce((a, c) => a + c.length, 0);
            const combined = new Uint8Array(total);
            let off = 0;
            chunks.forEach(c => { combined.set(c, off); off += c.length; });
            const b64 = uint8ArrayToBase64(combined);
            await FSAccess.writeFile({ uri: self._uri, data: b64 });
            self._size = total;
          },
          abort(reason) {
            chunks.length = 0;
          },
        };
      }

      async isSameEntry(other) {
        return other instanceof SAFFileHandle && other._uri === this._uri;
      }

      async remove() {
        throw new DOMException('Remove not supported with SAF', 'NotSupportedError');
      }
    }

    // --- SAF FileSystemDirectoryHandle ---
    class SAFDirectoryHandle {
      constructor(uri, name) {
        this.kind = 'directory';
        this._uri = uri;
        this._name = name || 'Directory';
      }

      get name() { return this._name; }

      async getFileHandle(name, { create = false } = {}) {
        // SAF 不支持通过名称直接获取子文件句柄
        // 需要先列出目录内容再匹配
        const entries = await this._listEntries();
        const found = entries.find(e => e.name === name && e.type === 'file');
        if (found) return new SAFFileHandle(found.uri, found.name, found.size);
        if (create) {
          throw new DOMException('Cannot create file via SAF directly. Use showSaveFilePicker instead.', 'NotSupportedError');
        }
        throw new DOMException('File not found: ' + name, 'NotFoundError');
      }

      async getDirectoryHandle(name, { create = false } = {}) {
        const entries = await this._listEntries();
        const found = entries.find(e => e.name === name && e.type === 'directory');
        if (found) return new SAFDirectoryHandle(found.uri, found.name);
        if (create) {
          throw new DOMException('Cannot create directory via SAF directly.', 'NotSupportedError');
        }
        throw new DOMException('Directory not found: ' + name, 'NotFoundError');
      }

      async removeEntry(name, { recursive = false } = {}) {
        throw new DOMException('Remove not supported with SAF', 'NotSupportedError');
      }

      async _listEntries() {
        try {
          const result = await FSAccess.listDirectory({ uri: this._uri });
          return result.entries || [];
        } catch (err) {
          console.error('[FS-Polyfill] SAF listDirectory 失败:', err);
          return [];
        }
      }

      async *values() {
        const entries = await this._listEntries();
        for (const entry of entries) {
          if (entry.type === 'directory') {
            yield new SAFDirectoryHandle(entry.uri, entry.name);
          } else {
            yield new SAFFileHandle(entry.uri, entry.name, entry.size);
          }
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
        return other instanceof SAFDirectoryHandle && other._uri === this._uri;
      }

      [Symbol.asyncIterator]() {
        return this.values();
      }
    }

    // ============================================================
    // SAF 版全局 API
    // ============================================================

    window.showDirectoryPicker = async function (options = {}) {
      const mode = options.mode || 'read';
      const result = await FSAccess.showDirectoryPicker({ mode });

      if (result.cancelled) {
        throw new DOMException('User cancelled', 'AbortError');
      }

      return new SAFDirectoryHandle(result.uri, result.name);
    };

    window.showOpenFilePicker = async function (options = {}) {
      const multiple = options.multiple || false;
      const result = await FSAccess.showOpenFilePicker({ multiple });

      if (result.cancelled) {
        throw new DOMException('User cancelled', 'AbortError');
      }

      const files = (result.files || []).map(f =>
        new SAFFileHandle(f.uri, f.name, f.size)
      );

      return files;
    };

    window.showSaveFilePicker = async function (options = {}) {
      const suggestedName = options.suggestedName || 'untitled.txt';
      const result = await FSAccess.showSaveFilePicker({ suggestedName });

      if (result.cancelled) {
        throw new DOMException('User cancelled', 'AbortError');
      }

      return new SAFFileHandle(result.uri, result.name);
    };

    window.FileSystemFileHandle = SAFFileHandle;
    window.FileSystemDirectoryHandle = SAFDirectoryHandle;

    // StorageManager.getDirectory polyfill - 返回持久化的目录
    if (navigator.storage && !navigator.storage.getDirectory) {
      navigator.storage.getDirectory = async function () {
        const result = await FSAccess.getPersistedUri();
        if (result.uri) {
          return new SAFDirectoryHandle(result.uri, 'Persisted Directory');
        }
        throw new DOMException('No persisted directory', 'NotFoundError');
      };
    }

  } else {
    // ============================================================
    // 回退方案 - 使用 Capacitor Filesystem（仅 Documents 目录）
    // ============================================================

    if (!Filesystem) {
      console.error('[FS-Polyfill] 没有 FSAccess 也没有 Filesystem 插件，无法提供 polyfill');
      return;
    }

    // --- Fallback FileSystemFileHandle ---
    class FallbackFileHandle {
      constructor(path, directory, name) {
        this.kind = 'file';
        this._path = path;
        this._directory = directory || 'DOCUMENTS';
        this._name = name || path.split('/').pop();
        this._size = 0;
      }

      get name() { return this._name; }

      async getFile() {
        try {
          const result = await Filesystem.readFile({
            path: this._path,
            directory: this._directory,
          });
          let data;
          if (typeof result.data === 'string') {
            try { data = base64ToUint8Array(result.data); }
            catch { data = encoder.encode(result.data); }
          }
          this._size = data ? data.length : 0;
          return new File([data], this._name, {
            type: 'application/octet-stream',
            lastModified: Date.now(),
          });
        } catch (err) {
          throw new DOMException('File not found', 'NotFoundError');
        }
      }

      async createWritable({ keepExistingData = false } = {}) {
        const self = this;
        const chunks = [];
        return {
          write(data) {
            if (typeof data === 'string') data = encoder.encode(data);
            if (data instanceof Uint8Array) chunks.push(data);
          },
          async close() {
            const total = chunks.reduce((a, c) => a + c.length, 0);
            const combined = new Uint8Array(total);
            let off = 0;
            chunks.forEach(c => { combined.set(c, off); off += c.length; });
            await Filesystem.writeFile({
              path: self._path,
              data: uint8ArrayToBase64(combined),
              directory: self._directory,
              recursive: true,
            });
            self._size = total;
          },
          abort() { chunks.length = 0; },
        };
      }

      async isSameEntry(other) {
        return other instanceof FallbackFileHandle &&
          other._path === this._path &&
          other._directory === this._directory;
      }
    }

    // --- Fallback FileSystemDirectoryHandle ---
    class FallbackDirectoryHandle {
      constructor(path, directory, name) {
        this.kind = 'directory';
        this._path = path;
        this._directory = directory || 'DOCUMENTS';
        this._name = name || 'Documents';
      }

      get name() { return this._name; }

      async getFileHandle(name, { create = false } = {}) {
        const childPath = this._path ? `${this._path}/${name}` : name;
        if (!create) {
          try {
            await Filesystem.stat({ path: childPath, directory: this._directory });
          } catch {
            throw new DOMException('File not found', 'NotFoundError');
          }
        }
        return new FallbackFileHandle(childPath, this._directory, name);
      }

      async getDirectoryHandle(name, { create = false } = {}) {
        const childPath = this._path ? `${this._path}/${name}` : name;
        if (create) {
          try {
            await Filesystem.mkdir({ path: childPath, directory: this._directory, recursive: true });
          } catch { /* 目录可能已存在 */ }
        }
        return new FallbackDirectoryHandle(childPath, this._directory, name);
      }

      async removeEntry(name, { recursive = false } = {}) {
        const childPath = this._path ? `${this._path}/${name}` : name;
        try {
          await Filesystem.deleteFile({ path: childPath, directory: this._directory });
        } catch {
          await Filesystem.rmdir({ path: childPath, directory: this._directory, recursive });
        }
      }

      async *values() {
        try {
          const result = await Filesystem.readdir({
            path: this._path || '.',
            directory: this._directory,
          });
          for (const entry of result.files) {
            const childPath = this._path ? `${this._path}/${entry.name}` : entry.name;
            if (entry.type === 'directory') {
              yield new FallbackDirectoryHandle(childPath, this._directory, entry.name);
            } else {
              yield new FallbackFileHandle(childPath, this._directory, entry.name);
            }
          }
        } catch (err) {
          console.error('[FS-Polyfill] 回退 readdir 失败:', err);
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
        return other instanceof FallbackDirectoryHandle &&
          other._path === this._path &&
          other._directory === this._directory;
      }

      [Symbol.asyncIterator]() {
        return this.values();
      }
    }

    // 回退版全局 API（无原生选择器，仅操作 Documents 目录）
    window.showDirectoryPicker = async function (options = {}) {
      console.warn('[FS-Polyfill] 无原生目录选择器，返回 Documents 目录');
      return new FallbackDirectoryHandle('', 'DOCUMENTS', 'Documents');
    };

    window.showOpenFilePicker = async function (options = {}) {
      console.warn('[FS-Polyfill] 无原生文件选择器，尝试列出 Documents 目录文件');
      const dir = new FallbackDirectoryHandle('', 'DOCUMENTS', 'Documents');
      const files = [];
      for await (const entry of dir.values()) {
        if (entry.kind === 'file') files.push(entry);
      }
      return files.slice(0, options.multiple ? undefined : 1);
    };

    window.showSaveFilePicker = async function (options = {}) {
      const name = options.suggestedName || 'untitled.txt';
      return new FallbackFileHandle(name, 'DOCUMENTS', name);
    };

    window.FileSystemFileHandle = FallbackFileHandle;
    window.FileSystemDirectoryHandle = FallbackDirectoryHandle;

    if (navigator.storage && !navigator.storage.getDirectory) {
      navigator.storage.getDirectory = async function () {
        return new FallbackDirectoryHandle('', 'DOCUMENTS', 'Documents');
      };
    }
  }

  // DataTransferItem.getAsFileSystemHandle polyfill
  if (window.DataTransferItem && !DataTransferItem.prototype.getAsFileSystemHandle) {
    DataTransferItem.prototype.getAsFileSystemHandle = async function () {
      if (this.kind === 'file') {
        const file = this.getAsFile();
        if (file) {
          if (useNativePicker) {
            // SAF 模式下无法从 DataTransfer 获取 URI
            console.warn('[FS-Polyfill] DataTransfer file access not supported with SAF');
            return null;
          }
          return new (window.FileSystemFileHandle)(file.name, 'DOCUMENTS', file.name);
        }
      }
      return null;
    };
  }

  console.log('[FS-Polyfill] File System Access API Polyfill 已安装完成 (' +
    (useNativePicker ? 'SAF 原生选择器' : 'Filesystem 回退') + ')');

  // 触发自定义事件，通知应用 polyfill 已就绪
  window.dispatchEvent(new CustomEvent('fs-polyfill-ready', {
    detail: {
      showOpenFilePicker: true,
      showSaveFilePicker: true,
      showDirectoryPicker: true,
      nativePicker: useNativePicker,
    }
  }));

})();