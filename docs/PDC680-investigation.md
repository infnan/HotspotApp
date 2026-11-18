# PDC680 热点问题排查记录

更新：用户已选择辅助功能方案，1.5 实现了开启时点击系统热点开关并回到桌面，关闭时沿用 1.0 接口。下面的直接接口排查属于历史记录；当前使用和真机验收以根目录 README.md 为准。尚未在 PDC680 上验证辅助点击及 DHCP。
## 已知实测

- 设备：Hytera PDC680，Android 7.1.2 / API 25，N2G47H release-keys，未 Root。
- 修改系统设置授权为 true。
- 系统设置开启热点后，客户端可自动获取地址并上网。
- 1.0 可开关热点，客户端不能自动获取地址；客户端手动配置 IP 后可上网。
- 1.3 在 HotspotController 构造函数调用 getTetherableWifiRegexs 时失败，系统原文为 You either need MANAGE_USERS or CREATE_USERS permission to: query user。尚未执行 startTethering。

## 源码核对结果

下列结论来自 AOSP 对应版本；海能达实际固件仍需采集，不能把 AOSP 实现当作其完整反编译结果。

1. 系统设置调用 ConnectivityManager.startTethering，而 1.0 只调用 WifiManager.setWifiApEnabled。
2. Tethering.setWifiTethering 先设置 mWifiTetherRequested，再开启 AP。收到 AP_ENABLED 事件后，只有该标志为 true 才会请求共享 Wi-Fi 接口。共享主状态机调用 NetworkManagementService.startTethering(mDhcpRange)。这解释了两个入口为什么不能等同，但不能仅凭 AOSP 断言海能达的转发状态。
3. ConnectivityService.getTetherableWifiRegexs、tether 和 startTethering 都会进入 isTetheringSupported。该方法调用 UserManager.isAdminUser，最终进入 UserManagerService.getUserInfo，后者要求 MANAGE_USERS 或 CREATE_USERS。
4. Android 7.1.2 r1 和 r39 中的上述用户检查没有清除调用身份。Android 8.0.0 r1 在同一位置使用 Binder.clearCallingIdentity / restoreCallingIdentity，并明确注释避免要求调用者持有 MANAGE_USERS。它与本次错误高度吻合，是系统服务身份处理问题的直接依据；海能达固件是否完全一致仍需验证。
5. MANAGE_USERS 在该版本为 signature|privileged，CREATE_USERS 为 signature。普通侧载应用不能通过常规运行时授权或 pm grant 获得。由应用自身调用 clearCallingIdentity 也不会获得 system_server 的 UID。

## 已核对的方向

| 方向 | 结果 |
| --- | --- |
| 再次开启修改系统设置授权 | 实测已授权，不能解决用户查询权限错误 |
| 仅删除 getTetherableWifiRegexs 查询 | 可避免当前报错位置，但 startTethering 本身也经过同一检查，不能据此宣布修复 |
| 先开 AP，再调用 ConnectivityManager.tether(interface) | 同样经过 isTetheringSupported，不能绕过该检查 |
| 直接请求 NetworkManagementService 启动 DHCP | 需要 CONNECTIVITY_INTERNAL 特权权限 |
| 修改 App 中的反射方式或清除 App 的 Binder 身份 | 不会把应用变成系统服务调用者 |
| 无障碍自动点击设置 | 用户后续已选择此方案，1.5 实现开启辅助点击、关闭直接调用，待真机验证 |
| 海能达厂商后台接口 | 需要设备上的服务、系统包及实际固件信息；当前尚无已验证接口 |
| ADB / shell 后台代理 | AOSP Shell 声明了 CREATE_USERS、WRITE_SETTINGS、ACCESS_NETWORK_STATE，值得在实际设备验证；不是向普通 App 直接授权。需要启用 USB 调试、电脑连接并授权，且代理重启后的存活与 ROM 限制均未验证 |

## 下一步真机只读采集

脚本 tools/collect-device-diagnostics.ps1 不切换热点、不安装应用、不授予权限、不修改手机文件。输出到 diagnostics/ 下的独立目录，失败的项目会记录而不冒充采集成功。报告可能含 Wi-Fi 名称、IP 地址和热点密码，应在分享前查看。

项目已准备好官方 Android SDK Platform-Tools，脚本会自动使用 .tools/android-sdk/platform-tools/adb.exe。先连接 USB 并允许调试；使用其他位置的 ADB 时可通过 -AdbPath 指定。

建议依次记录：

1. 系统设置关闭热点，运行 baseline 采集，可加 -IncludeSystemPackages 保存系统框架及设置安装包。
2. 系统设置开启热点并确认另一设备自动获取地址，运行 system-hotspot 采集。
3. 若手头仍有原始 1.0，系统设置关闭热点后用 1.0 开启，再运行 app-hotspot 采集。没有 1.0 时不要把当前 APK 当作 1.0。

示例：

~~~powershell
.\tools\collect-device-diagnostics.ps1 -Stage baseline -IncludeSystemPackages
.\tools\collect-device-diagnostics.ps1 -Stage system-hotspot
.\tools\collect-device-diagnostics.ps1 -Stage app-hotspot
~~~

查看具体固件内的共享实现和厂商后台入口后，再决定是否制作候选修复包。本轮未更改热点控制代码，也未生成宣称修复成功的新 APK。

## 验证范围

- 本地验证了采集脚本语法、ADB 缺失及没有已授权设备时的明确错误，未执行真机采集。
- 已准备官方 ADB，实际执行 adb devices -l 返回空列表；当前没有已连接并可用的 ADB 设备。
- 应用仍未实现 PDC680 上经过验证的一键完整共享。

## 原始源码

- [Android 7.1.2 ConnectivityService](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/services/core/java/com/android/server/ConnectivityService.java)
- [Android 7.1.2 Tethering](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/services/core/java/com/android/server/connectivity/Tethering.java)
- [Android 7.1.2 UserManager](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/core/java/android/os/UserManager.java)
- [Android 7.1.2 UserManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/services/core/java/com/android/server/pm/UserManagerService.java)
- [Android 7.1.2 权限定义](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/core/res/AndroidManifest.xml)
- [Android 8.0.0 的身份修复](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/ConnectivityService.java)
- [Android 7.1.2 网络管理服务](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/services/core/java/com/android/server/NetworkManagementService.java)
- [Android 7.1.2 Shell 权限](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r1/packages/Shell/AndroidManifest.xml)
