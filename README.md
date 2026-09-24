# 16路 USB 距离监测 Android App

原生 Android USB Host 应用，面向两片 WCH CH348 八串口芯片，共16路。

## 功能

- 两片 CH348、16路同时接收
- 固定串口参数：115200、8N1、无流控
- 解析 `distances= 268mm` 格式并实时显示
- “设备1 A-H”对应第1-8层，“设备2 A-H”对应第9-16层
- 两片相同设备枚举顺序变化时，可点击“交换两组”并永久保存
- 开始/结束记录，CSV保存到 Android 的 `下载/串口距离记录`
- USB插拔检测、权限请求、断开提示

## 硬件要求

1. Android 10或更高版本，必须支持 USB Host/OTG。
2. 带独立电源的USB 2.0/3.0 Hub。不要依赖平板给32/64个雷达模块供电。
3. CH348 默认识别码为 VID `0x1A86`、PID `0x55D9`。
4. 两片CH348的A-H端口分别接第1-8层和第9-16层。

## 安装和首次使用

1. 安装 `app-debug.apk`。
2. 先给USB Hub和传感器上电，再通过OTG连接Android设备。
3. 打开App，对两台CH348分别允许USB访问。
4. 检查页面是否显示“全部在线 · 16/16”。
5. 如果上下8层颠倒，点击“交换两组”。

## 构建

项目使用 Java 17、Gradle 8.9、Android Gradle Plugin 8.7.3、compileSdk 35。

```bash
gradle assembleDebug
```

APK输出：`app/build/outputs/apk/debug/app-debug.apk`

仓库包含 `.github/workflows/build-apk.yml`，推送到GitHub后会自动构建并上传APK artifact。

## 重要说明

CH348把8路串口复用在同一组USB Bulk端点中，并不是8个普通CH340。此项目包含用户态CH348协议实现。第一次连接真实硬件时，应核对设备VID/PID、端点地址和A-H顺序；若购买的板卡修改了PID，需要同步修改 `device_filter.xml` 和 `Ch348Device.java`。
