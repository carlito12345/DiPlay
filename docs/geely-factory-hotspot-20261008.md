# 原厂热点识别修复：0.2.14 支线

carlito | 2026-10-08

## 已核对的证据

- 两份 KX11 Android 9 报告的无线启动均停在 MANUAL 热点就绪：Android tethered 列表为空，wlan0/p2p0 未启用，eth* 只暴露 IPv6 link-local。尚未进入无线认证、服务发现和媒体。
- 支线报告中的 USB 已完成 MFi 认证、IPv6 AirPlay TCP、画面首帧及媒体播放。不能据此把无线问题归因于车机完全不支持 IPv6，也不能据此认定通话采集正常。
- 重新读取用户提供的 com.geely.settings APK，确认 WifiAp/Wifi6Ap、IConnectable、客户端 getIP/getMac 等接口引用。该 APK 未定义这些 SDK 类；反射调用仍需车机提供可读 SDK 和服务授权。未发现该组接口能读取热点名称、密码。

## 实现边界

现有 ManualHotspotInterfaces 仅增加一个 EcarxHotspotReader。没有另一套 manager、握手、Bonjour 或自动重试流程。

1. 原厂客户端 IP 用于确认本机路由；IPv4 只能使用真实本机地址和唯一同子网匹配，IPv6 必须核对接口 scope。
2. SDK 缺少 IPv6 scope 或只有 IPv4、本机只见 IPv6 时，可将 SDK 当前客户端 Wi-Fi MAC 与系统 IPv6 邻居表关联。邻居查询有 400ms 上限，只有匹配的 link-local 客户端、启用的真实接口和实际本机地址能成为证据。
3. 多个原厂接口无法关联到保存的 SSID 时拒绝猜测。无客户端路由/邻居证据的 Ethernet 继续拒绝；未恢复泛化私网或 198.18.* 例外。
4. 原厂路径使用一组保存的 SSID、密码、安全类型；不读取另一套 Android SoftAP 配置，也不把 Ethernet MAC 当作 Wi-Fi BSSID。监听、服务发现和无线邀请沿用同一主地址。
5. SDK 存在时，Android SoftAP 关闭不代表独立原厂热点关闭；仍必须通过上述通路验证。普通 WLAN 保留 Android AP 的原始关闭判定，不能因 SDK 存在而放行残留地址。自动启动选项不借 Android/ADB 重建这个未知原厂热点。
6. SDK 连接和邻居进程由现有管理器释放；诊断只读取缓存。接口地址或归属来源改变会重置稳定检测。

## 验证与实际限制

新增回归覆盖数字 IP 解析、MAC/邻居关联、IPv6 scope、地址消失、接口关闭、来源改变，以及当前日志中没有证据的 Ethernet 仍然被拒绝。保留网络、USB/iAP2、AirPlay、媒体、麦克风与热点设置回归。

编译、单元测试和独立源码审阅不能证明厂商 Binder 授权、SELinux 邻居访问、实际热点转发或手机连接成功。原厂热点完全未向 DiPlay 暴露可达接口时，程序不能凭空制造本机 IPv4，也不能把任意以太网认作热点。首次连接尚无厂商客户端/通路证据时，可能仍需先让手机加入原厂热点。

版本维持 0.2.14；主线、USB及此前麦克风修复保持独立。
