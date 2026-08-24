#import <Capacitor/Capacitor.h>

// 定义插件导出方法
CAP_PLUGIN(FSAccessPlugin, "FSAccess",
    CAP_PLUGIN_METHOD(showDirectoryPicker, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(showOpenFilePicker, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(showSaveFilePicker, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(readFile, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(writeFile, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(listDirectory, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getPersistedUri, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestAllPermissions, CAPPluginReturnPromise);
)