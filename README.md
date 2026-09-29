# SeleneLiveTV

仅保留 TV 直播功能的 Android TV 工程，最低支持 Android 9（API 28）。

## 当前版本

- 版本：`1.4.6-android9-mpv-debug-ui`
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


## 屏幕播放诊断

右上角会实时显示 MPV 播放阶段，用于定位直播卡顿位置：

- START_FILE：已开始打开直播地址
- FILE_LOADED：直播流已打开并完成基础解析
- VIDEO_RECONFIG：视频解码器/视频参数已经建立
- PLAYBACK_RESTART：实际播放已经启动或缓冲后恢复
- 缓存百分比、缓存秒数、视频/音频编码、分辨率、播放时间
- 若播放时间连续约 3.5 秒不增长，会直接显示“播放时间已停止”


## 自动恢复策略（1.4.5）

启动时优先使用本地已解析的 `live.m3u` 缓存。若缓存频道能正常播放，不依赖网络更新订阅。

当当前缓存频道出现以下异常时自动恢复：
- 已经 FILE_LOADED 且识别到视频参数，但约 6 秒仍未真正开始播放；
- 直播地址约 9 秒仍未打开；
- 连续缓冲约 12 秒；
- 播放过程中 time-pos 超过约 8 秒不增长；
- 播放开始前收到 END_FILE。

恢复顺序：
1. 先把当前频道切到软件解码再试一次；
2. 软件解码仍失败，则后台重新下载 Selene 订阅和最新 `live.m3u`；
3. 新订阅下载成功后写回本地缓存，优先按同名频道重新播放；
4. 更新失败则保留原缓存，不清空频道列表。


## 调试窗口交互（1.4.6）

- 返回键：频道列表打开时先关闭频道列表；否则若直播调试窗口可见，则只隐藏调试窗口，不退出应用。
- 切换频道：调试窗口重新显示。
- 真正连续播放约 2 秒且播放进度持续增长后，调试窗口自动隐藏。
- 若后续播放卡住并触发自动恢复，调试窗口会重新显示。
