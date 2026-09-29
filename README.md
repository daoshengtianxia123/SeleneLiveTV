# SeleneLiveTV

仅保留 TV 直播功能的 Android TV 工程，最低支持 Android 9（API 28）。

## 当前版本

- 版本：`1.4.3-android9-mpv-cachefirst`
- 默认订阅：`https://raw.githubusercontent.com/daoshengtianxia123/selene-iptv/main/selene-sub.txt`
- 支持 Selene Base58 订阅：先解码 JSON，再读取 `lives.*.url` 对应的 M3U。
- 播放核心：`dev.jdtech.mpv:libmpv:1.0.0`（libmpv + FFmpeg）。
- 遥控器：↑ 上一台、↓ 下一台、OK 频道列表、菜单键进入订阅设置。
- 切台复用同一个 Activity/SurfaceView，使用 MPV `loadfile URL replace`。
- 本地缓存优先：有缓存时启动直接播放；6 小时内不访问网络；过期后后台静默刷新。
- 后台订阅更新失败不会打断当前缓存播放。

## 编译环境

- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- compileSdk 36
- targetSdk 35
- minSdk 28（Android 9）
- ABI：armeabi-v7a

首次 Gradle Sync 需要联网下载 `dev.jdtech.mpv:libmpv:1.0.0`。

## 第三方组件

- mpv: https://github.com/mpv-player/mpv
- FFmpeg: https://ffmpeg.org
- libmpv Android packaging: https://github.com/jarnedemeulemeester/libmpv-android

许可证说明位于 `app/src/main/assets/licenses/`。
