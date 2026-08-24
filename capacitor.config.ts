import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.md6.app',
  appName: 'MD6',
  webDir: 'www',
  // 加载远程 URL，Capacitor 会自动注入桥接脚本
  server: {
    url: 'https://md6.pnt.pp.ua/',
    // 开发时可以使用本地地址
    // url: 'http://localhost:3000/',
    androidScheme: 'https',
    androidAllowMixedContent: true,
    // 允许导航的域名
    allowNavigation: ['md6.pnt.pp.ua', '*.pnt.pp.ua'],
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2000,
      launchAutoHide: true,
      backgroundColor: '#ffffff',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: true,
    },
    StatusBar: {
      style: 'LIGHT',
      backgroundColor: '#ffffff',
    },
    Filesystem: {
      // 允许在 Documents 目录下操作
      defaultDirectory: 'DOCUMENTS',
    },
  },
  android: {
    // 允许 HTTP 混合内容
    allowMixedContent: true,
  },
  ios: {
    // 允许访问文件
    contentInset: 'automatic',
  },
};

export default config;