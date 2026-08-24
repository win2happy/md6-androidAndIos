#!/bin/bash
# ============================================================
# iOS 原生代码补丁脚本
# 
# 在 `npx cap add ios` 后运行，将自定义的 AppDelegate.swift
# 和 FSAccessPlugin 复制到 iOS 项目中，
# 注入 FS Access API Polyfill + UIDocumentPicker 文件选择器
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

if [ -f "$IOS_APP_DIR/AppDelegate.swift" ]; then
    echo "  ✅ AppDelegate.swift 已复制到: $IOS_APP_DIR/AppDelegate.swift"
else
    echo "  ❌ AppDelegate.swift 复制失败"
    exit 1
fi

# 复制 FSAccessPlugin.swift（UIDocumentPicker 原生文件选择器插件）
echo "📝 复制 FSAccessPlugin.swift ..."
cp "$PROJECT_ROOT/native/ios/FSAccessPlugin.swift" "$IOS_APP_DIR/FSAccessPlugin.swift"

if [ -f "$IOS_APP_DIR/FSAccessPlugin.swift" ]; then
    echo "  ✅ FSAccessPlugin.swift 已复制到: $IOS_APP_DIR/FSAccessPlugin.swift"
else
    echo "  ❌ FSAccessPlugin.swift 复制失败"
    exit 1
fi

# 复制 FSAccessPlugin.m（Objective-C 插件注册文件）
echo "📝 复制 FSAccessPlugin.m ..."
cp "$PROJECT_ROOT/native/ios/FSAccessPlugin.m" "$IOS_APP_DIR/FSAccessPlugin.m"

if [ -f "$IOS_APP_DIR/FSAccessPlugin.m" ]; then
    echo "  ✅ FSAccessPlugin.m 已复制到: $IOS_APP_DIR/FSAccessPlugin.m"
else
    echo "  ❌ FSAccessPlugin.m 复制失败"
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
        
        # 通知权限描述
        if ! /usr/libexec/PlistBuddy -c "Print :UIBackgroundModes" "$INFO_PLIST" &> /dev/null; then
            /usr/libexec/PlistBuddy -c "Add :UIBackgroundModes array" "$INFO_PLIST"
            /usr/libexec/PlistBuddy -c "Add :UIBackgroundModes:0 string 'remote-notification'" "$INFO_PLIST"
            echo "  ✅ 添加 UIBackgroundModes: remote-notification"
        else
            # 检查是否已有 remote-notification
            if ! /usr/libexec/PlistBuddy -c "Print :UIBackgroundModes" "$INFO_PLIST" | grep -q "remote-notification"; then
                /usr/libexec/PlistBuddy -c "Add :UIBackgroundModes:0 string 'remote-notification'" "$INFO_PLIST"
                echo "  ✅ 添加 UIBackgroundModes: remote-notification"
            fi
        fi
        
        # LSSupportsOpeningDocumentsInPlace - 允许文件访问
        if ! /usr/libexec/PlistBuddy -c "Print :LSSupportsOpeningDocumentsInPlace" "$INFO_PLIST" &> /dev/null; then
            /usr/libexec/PlistBuddy -c "Add :LSSupportsOpeningDocumentsInPlace bool true" "$INFO_PLIST"
            echo "  ✅ 添加 LSSupportsOpeningDocumentsInPlace"
        fi
        
        # UIFileSharingEnabled - 允许文件共享
        if ! /usr/libexec/PlistBuddy -c "Print :UIFileSharingEnabled" "$INFO_PLIST" &> /dev/null; then
            /usr/libexec/PlistBuddy -c "Add :UIFileSharingEnabled bool true" "$INFO_PLIST"
            echo "  ✅ 添加 UIFileSharingEnabled"
        fi
    else
        echo "  ⚠️  PlistBuddy 不可用，跳过 Info.plist 修改"
    fi
    
    echo "✅ Info.plist 已更新"
else
    echo "⚠️  Info.plist 未找到: $INFO_PLIST"
fi

# 确保 AppDelegate 中调用 setupFileSystemPolyfill
echo "📝 检查 AppDelegate 注入方法调用 ..."
if ! grep -q "setupFileSystemPolyfill" "$IOS_APP_DIR/AppDelegate.swift"; then
    echo "  ℹ️  注入方法将在 CAPBridgeViewController 扩展中自动调用"
fi

echo ""
echo "========================================"
echo "  ✅ iOS 原生代码补丁完成"
echo "========================================"
echo ""
echo "  已添加的功能:"
echo "  - FSAccessPlugin: UIDocumentPicker 原生文件/目录选择器"
echo "  - 通知权限请求: alert + badge + sound"
echo "  - Info.plist: 文件访问/共享/通知权限"
echo ""