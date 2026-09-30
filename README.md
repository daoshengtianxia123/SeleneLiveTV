# SeleneLiveTV

仅保留 TV 直播功能的 Android TV 工程，最低支持 Android 9（API 28）。

## 当前版本

- 版本：`1.4.8-android9-bundled-fallback`
- 默认订阅：`https://gitee.com/daoshengtianxia/selene-iptv/raw/main/selene-sub.txt`
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


## 离线启动缓存（1.4.7）

- 成功获取直播列表后，同时写入应用内部文件 `playlist_cache.m3u` 和 SharedPreferences。
- 启动时优先读取内部文件缓存；文件无效时再读取 SharedPreferences。
- 只要本地存在可解析频道，启动时立即使用缓存播放，不先等待 GitHub/VPN。
- 缓存超过 6 小时时，延迟约 15 秒后台静默刷新订阅；刷新失败不影响当前缓存播放。
- 完全没有本地缓存时，才显示“本地没有频道缓存，正在首次加载直播订阅…”并联网获取。
- 调试窗口把“订阅缓存”和“直播源服务器”分开显示，避免把订阅网络问题与直播源卡顿混在一起。


## 首次安装无 VPN 兜底（1.4.8）

频道来源现在按三层优先级读取：
1. 应用内部文件缓存 `playlist_cache.m3u`
2. SharedPreferences 备份
3. APK 内置 `assets/fallback_live.m3u`

只要任意一层能解析出频道，启动时就先显示频道并尝试播放，不等待 GitHub。

订阅更新仍然使用原来的 Selene Base58 地址，但内部下载顺序对 GitHub Raw 做了优化：
1. `cdn.jsdelivr.net/gh/...`
2. `raw.githubusercontent.com/...`
3. GitHub raw 备用入口

这样不开 VPN 时更容易先拿到订阅，而直播频道播放仍直接访问频道自己的 URL，不经过订阅 CDN。


## Gitee 主订阅（1.4.9）

默认订阅已切换到中国大陆 Gitee：

```
https://gitee.com/daoshengtianxia/selene-iptv/raw/main/selene-sub.txt
```

订阅下载顺序：
1. Gitee
2. jsDelivr
3. GitHub Raw
4. GitHub raw 备用入口
5. 本地/内置缓存继续兜底

兼容旧版本保存的 GitHub Raw 地址：程序会自动优先尝试对应的 Gitee 镜像。


## 无人值守 TV 逻辑（1.5.0）

面向开机自动启动、用户只使用上下键换台的设备：

- 启动优先使用本地 `live.m3u`，不等待网络。
- 成功网络更新时同时缓存 `selene-sub.txt` 和最终 `live.m3u`，均保存到内部存储并保留 Preferences 备份。
- 旧版保存的 GitHub Raw 默认订阅地址自动迁移为 Gitee 默认地址。
- 阶段 1/4 超过约 9 秒未打开直播流，自动恢复。
- 阶段 2/4 超过约 10 秒仍未开始播放，自动恢复。
- 正常播放后若连续缓冲约 12 秒或播放时间超过约 8 秒不增长，也自动恢复。
- 恢复顺序：软件解码重试 → 后台刷新订阅（至少间隔 60 秒）→ 用同名新地址重播 → 仍失败自动切到下一个频道。
- 后台更新失败不会自动进入设置页，不要求用户处理。
