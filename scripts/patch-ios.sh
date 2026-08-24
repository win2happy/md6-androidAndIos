#!/bin/bash
# ============================================================
# iOS 原生代码补丁脚本
# 
# 在 `npx cap add ios` 后运行，将自定义的 AppDelegate.swift
# 复制到 iOS 项目中，注入 FS Access API Polyfill
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IOS_APP_DIR="$PROJECT_ROOT/ios/App/App"

echo "========================================"
echo "  MD6 iOS 原生代码补丁"
echo "========================================"

# 检查 iOS 项目是否存在
if [ ! -d "$PROJECT_ROOT/ios" ]; then
    echo "❌ iOS 项目不存在，请先运行: npx cap add ios"
    exit 1
fi

echo "📁 iOS 项目目录: $PROJECT_ROOT/ios"

# 复制自定义 AppDelegate.swift
echo "📝 复制 AppDelegate.swift ..."
cp "$PROJECT_ROOT/native/ios/AppDelegate.swift" "$IOS_APP_DIR/AppDelegate.swift"

# 验证复制结果
if [ -f "$IOS_APP_DIR/AppDelegate.swift" ]; then
    echo "✅ AppDelegate.swift 已复制到: $IOS_APP_DIR/AppDelegate.swift"
else
    echo "❌ AppDelegate.swift 复制失败"
    exit 1
fi

# 修改 Info.plist 添加必要的权限描述
INFO_PLIST="$IOS_APP_DIR/Info.plist"
if [ -f "$INFO_PLIST" ]; then
    echo "📝 修改 Info.plist 添加权限描述 ..."
    
    # 使用 PlistBuddy 添加权限（如果存在则跳过）
    if command -v /usr/libexec/PlistBuddy &> /dev/null; then
        # NSDocumentsFolderUsageDescription
        if ! /usr/libexec/PlistBuddy -c "Print :NSDocumentsFolderUsageDescription" "$INFO_PLIST" &> /dev/null; then
            /usr/libexec/PlistBuddy -c "Add :NSDocumentsFolderUsageDescription string 'MD6 needs access to documents for file operations.'" "$INFO_PLIST"
            echo "  ✅ 添加 NSDocumentsFolderUsageDescription"
        fi
        
        # NSPhotoLibraryAddUsageDescription
        if ! /usr/libexec/PlistBuddy -c "Print :NSPhotoLibraryAddUsageDescription" "$INFO_PLIST" &> /dev/null; then
            /usr/libexec/PlistBuddy -c "Add :NSPhotoLibraryAddUsageDescription string 'MD6 needs access to photo library to save files.'" "$INFO_PLIST"
            echo "  ✅ 添加 NSPhotoLibraryAddUsageDescription"
        fi
    else
        echo "  ⚠️  PlistBuddy 不可用，跳过 Info.plist 修改"
    fi
    
    echo "✅ Info.plist 已更新"
else
    echo "⚠️  Info.plist 未找到: $INFO_PLIST"
fi

# 修改 AppDelegate 调用 setupFileSystemPolyfill
# 需要在 viewDidLoad 后调用注入方法
echo "📝 修改 AppDelegate 确保注入方法被调用 ..."

# 检查是否已有注入调用
if ! grep -q "setupFileSystemPolyfill" "$IOS_APP_DIR/AppDelegate.swift"; then
    # 在 didFinishLaunchingWithOptions 中添加调用
    # 注意：由于 AppDelegate 继承自 UIResponder，注入需要通过 CAPBridgeViewController
    echo "  ℹ️  注入方法将在 CAPBridgeViewController 扩展中自动调用"
fi

echo ""
echo "========================================"
echo "  ✅ iOS 原生代码补丁完成"
echo "========================================"