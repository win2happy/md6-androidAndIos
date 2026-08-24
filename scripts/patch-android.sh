#!/bin/bash
# ============================================================
# Android 原生代码补丁脚本
# 
# 在 `npx cap add android` 后运行，将自定义的 MainActivity.java
# 和 FSAccessPlugin.java 复制到 Android 项目中，
# 注入 FS Access API Polyfill + SAF 原生文件选择器
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_APP_DIR="$PROJECT_ROOT/android/app/src/main/java/com/md6/app"

echo "========================================"
echo "  MD6 Android 原生代码补丁"
echo "========================================"

# 检查 Android 项目是否存在
if [ ! -d "$PROJECT_ROOT/android" ]; then
    echo "❌ Android 项目不存在，请先运行: npx cap add android"
    exit 1
fi

echo "📁 Android 项目目录: $PROJECT_ROOT/android"

# 创建目标目录
mkdir -p "$ANDROID_APP_DIR"

# 复制自定义 MainActivity.java
echo "📝 复制 MainActivity.java ..."
cp "$PROJECT_ROOT/native/android/MainActivity.java" "$ANDROID_APP_DIR/MainActivity.java"

if [ -f "$ANDROID_APP_DIR/MainActivity.java" ]; then
    echo "  ✅ MainActivity.java 已复制到: $ANDROID_APP_DIR/MainActivity.java"
else
    echo "  ❌ MainActivity.java 复制失败"
    exit 1
fi

# 复制 FSAccessPlugin.java（SAF 原生文件选择器插件）
echo "📝 复制 FSAccessPlugin.java ..."
cp "$PROJECT_ROOT/native/android/FSAccessPlugin.java" "$ANDROID_APP_DIR/FSAccessPlugin.java"

if [ -f "$ANDROID_APP_DIR/FSAccessPlugin.java" ]; then
    echo "  ✅ FSAccessPlugin.java 已复制到: $ANDROID_APP_DIR/FSAccessPlugin.java"
else
    echo "  ❌ FSAccessPlugin.java 复制失败"
    exit 1
fi

# 修改 AndroidManifest.xml 添加必要的权限
MANIFEST_FILE="$PROJECT_ROOT/android/app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST_FILE" ]; then
    echo "📝 修改 AndroidManifest.xml 添加权限 ..."
    
    # 网络权限
    if ! grep -q "INTERNET" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.INTERNET" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 INTERNET 权限"
    fi
    
    # 存储权限（Android 12 及以下）
    if ! grep -q "READ_EXTERNAL_STORAGE" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 READ_EXTERNAL_STORAGE 权限"
    fi
    
    if ! grep -q "WRITE_EXTERNAL_STORAGE" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 WRITE_EXTERNAL_STORAGE 权限"
    fi
    
    # Android 13+ 细化媒体权限
    if ! grep -q "READ_MEDIA_IMAGES" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 READ_MEDIA_IMAGES 权限"
    fi
    
    if ! grep -q "READ_MEDIA_VIDEO" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 READ_MEDIA_VIDEO 权限"
    fi
    
    if ! grep -q "READ_MEDIA_AUDIO" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 READ_MEDIA_AUDIO 权限"
    fi
    
    # 通知权限（Android 13+）
    if ! grep -q "POST_NOTIFICATIONS" "$MANIFEST_FILE"; then
        sed -i 's|</manifest>|    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n</manifest>|' "$MANIFEST_FILE"
        echo "  ✅ 添加 POST_NOTIFICATIONS 通知权限"
    fi
    
    echo "✅ AndroidManifest.xml 已更新"
else
    echo "⚠️  AndroidManifest.xml 未找到: $MANIFEST_FILE"
fi

# 修改 strings.xml 设置应用名称
STRINGS_FILE="$PROJECT_ROOT/android/app/src/main/res/values/strings.xml"
if [ -f "$STRINGS_FILE" ]; then
    echo "📝 修改 strings.xml 设置应用名称 ..."
    sed -i 's|<string name="app_name">.*</string>|<string name="app_name">MD6</string>|' "$STRINGS_FILE"
    echo "  ✅ 应用名称设置为: MD6"
fi

# 修改 colors.xml 设置主题色
COLORS_FILE="$PROJECT_ROOT/android/app/src/main/res/values/colors.xml"
if [ ! -f "$COLORS_FILE" ]; then
    mkdir -p "$PROJECT_ROOT/android/app/src/main/res/values"
    cat > "$COLORS_FILE" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="colorPrimary">#ffffff</color>
    <color name="colorPrimaryDark">#ffffff</color>
    <color name="colorAccent">#333333</color>
</resources>
EOF
    echo "  ✅ 创建 colors.xml"
fi

echo ""
echo "========================================"
echo "  ✅ Android 原生代码补丁完成"
echo "========================================"
echo ""
echo "  已添加的功能:"
echo "  - FSAccessPlugin: SAF 原生文件/目录选择器"
echo "  - 运行时权限请求: 存储 + 通知"
echo "  - AndroidManifest 权限: 存储/媒体/通知"
echo ""