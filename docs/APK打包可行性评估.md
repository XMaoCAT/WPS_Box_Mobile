# 小米 WPS PC 版打包成 APK 的可行性评估

评估日期：2026-11-11
评估对象：`Xiaomi_PC_WPS_install_package_e9cb40a4.zip`（1.21 GB）

---

## 一、这次评估与上一版结论的关键差异

上一轮调研得出"现代 targetSdk 应用完全无法 execve，因此必须锁 targetSdk 28，无法上架商店"的硬结论。**本轮在设备上直接核实 AOSP 源码后，这个结论需要修正**——修正结果对本项目是有利的。

### 核实过程

我拉取了 LineageOS 21（对应 Android 14）的 sepolicy 源码逐字比对：

**第一处，`public/global_macros` 第 24 行**：

```
define(`x_file_perms', `{ getattr execute execute_no_trans map }')
```

`x_file_perms` **包含 `execute` 与 `execute_no_trans`**。

**第二处，`private/app.te` 第 436-437 行**：

```
# Allow apps to read/execute installed binaries
allow appdomain apk_data_file:dir { open getattr read search ioctl lock };
allow appdomain apk_data_file:file { getattr open read ioctl lock map x_file_perms };
```

注释原文就是 "Allow apps to read/execute installed binaries"。**所有应用域（含 `untrusted_app`）被显式允许执行 `apk_data_file`。**

**第三处，`private/app_neverallows.te` 第 59-69 行**：

```
# Block calling execve() on files in an apps home directory.
# This is a W^X violation (loading executable code from a writable
# home directory). For compatibility, allow for targetApi <= 28.
# b/112357170
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```

关键在于被禁的类型是 **`app_data_file`（应用私有 home 目录）和 `privapp_data_file`**，**`apk_data_file` 不在禁止之列**。

**第四处，`private/app.te` 第 512-524 行**，另一条 neverallow 显式把 `apk_data_file` 排除在外：

```
neverallow {
  ...
  appdomain
  ...
} {
  data_file_type
  -apex_art_data_file
  -dalvikcache_data_file
  -system_data_file # shared libs in apks
  -apk_data_file
}:file no_x_file_perms;
```

注释写着 `# shared libs in apks`，即**有意为 APK 内的共享库开这个口子**。

**第五处，设备实测证据**。当前宿主应用 `com.dshbox.app` 的已映射库文件路径为：

```
/data/app/~~PXuVS2XwOnRprmA3_ZgPUw==/com.dshbox.app-LsiLQ79-hHr20cTZ-7cAhg==/lib/arm64/libproot-loader.so
```

这正是 `apk_data_file` 类型的原生库目录，且该应用本身就在其中承载了完整的 proot 运行时。

### 修正后的结论

| 位置 | SELinux 类型 | 现代 targetSdk 能否 execve |
|---|---|---|
| 应用私有 home（`/data/data/<pkg>/files`） | `app_data_file` | **不能** |
| APK 原生库目录（`/data/app/.../lib/arm64/`） | `apk_data_file` | **能** |
| 外部存储 / 媒体区 | `storage_area_content_file` | 不能（Android 15 起） |

**可执行文件放进 APK 的 `jniLibs/arm64-v8a/`，以 `lib*.so` 命名，由 PackageManager 解包到原生库目录后是可以被 `execve` 的。** 这条路不需要把 targetSdk 锁在 28，因此不构成"无法上架商店"的死结。

### 需要说明的边界

- 前提是 `android:extractNativeLibs="true"`（库被真实解包成磁盘文件）。若设为 `false`，库只从 APK 内 mmap，磁盘上不存在可 execve 的路径。
- 文件名必须匹配 `lib*.so`，PackageManager 只解包这个模式。
- Android 15（LineageOS 22.1）新增的 `RELEASE_UNLOCKED_STORAGE_API` 规则只针对 `storage_area_content_file`，注释明确说明这是为了"阻止应用在存储区执行文件"，而 `apk_data_file` 未被波及。
- 该目录有空间与数量约束，塞入 2.5 GB 级别的载荷属于非常规用法，需实测 OEM 行为（置信度：中）。

---

## 二、已验证的运行事实

以下均在设备上实测通过，有截图为证（见 `verify/` 目录）：

| 验证项 | 结果 |
|---|---|
| WPS 依赖闭环 | 完整，零 "not found" |
| glibc 需求 | 本体最高仅需 **GLIBC_2.17**，宿主 2.41 原生兼容 |
| Qt 平台插件 | 仅 `libqxcb.so`，**X11 强制**，无 Wayland/offscreen 可用构建 |
| Writer / Spreadsheets / Presentation | **三大组件全部启动成功**，界面与 Windows 版一致 |
| 中文渲染 | 补齐文泉驿字体后完整正确 |
| 表格数据解析 | CSV 正确读入，单元格编辑栏、工作表标签正常 |
| 文档输入 | 中文输入成功，状态栏 Words 计数正确 |
| 渲染路径 | Mesa swrast 软件渲染，`/dev/dri` 拒绝访问 |
| 显示后端 | Xvfb（1600x1000）成功，weston + Xwayland 亦可 |

---

## 三、体积测算

| 项目 | 未压缩 | 说明 |
|---|---|---|
| `office6`（WPS 本体） | 1275 MiB | 含主程序、Qt、插件 |
| `lib/aarch64-linux-gnu` | 1277 MiB | 系统库、Mesa、字体依赖 |
| `share` | 350 MiB | 字体、xkb 等 |
| **rootfs 合计** | **3.0 GiB** | |

**实测压缩率 27-29%**（`libkso.so` 92,810,104 → 25,342,054 字节；`wps` 2,495,552 → 746,210 字节）。

按此推算，**打包后约 830-890 MiB**。

可裁剪项：

| 项目 | 大小 | 可否裁剪 |
|---|---|---|
| `addons/cef` | 199 MiB | 视功能取舍，见下 |
| `office6/mui` | 124 MiB | 可裁至单语言 |
| 其余 addons | 约 260 MiB | 视功能取舍 |

`libcef.so`（149,639,416 字节）位于 `addons/cef/`，**`wps` 主程序并未直接链接它**。CEF 主要服务于云文档、内嵌网页等在线功能，离线核心编辑不依赖它。裁掉 CEF 是体积优化的最大单点，但会损失部分在线能力。

---

## 四、分发渠道的硬约束

| 渠道 | 限制 | 对本项目的影响 |
|---|---|---|
| Google Play APK 下载大小 | **100 MB** | 直接内置不可行 |
| APK 扩展文件（OBB） | 最多 2 个，每个 ≤ 2 GB | 830 MiB 可拆入 2 个 OBB |
| Play Asset Delivery | 单包 ≤ 1 GB，且**不得含可执行代码** | 我们的载荷含可执行 ELF，政策上有风险 |
| 侧载 | 无硬限制 | 最宽松 |

结论：**走商店必须首启下载**（这也是 Andronix、UserLAnd、Winlator 的共同做法）；**侧载则可以整包内置**。

---

## 五、工作量估算

以 2-3 人团队、已有本次验证成果为起点。

| 工作流 | 人·天 | 性质 |
|---|---|---|
| rootfs 合成（补 `/etc`、`ld.so.cache`、locale、CA、设备节点、重建符号链接与权限） | 15-25 | 繁琐但确定 |
| rootfs 瘦身（3.0 GB → 1.5 GB 级，裁 CEF/多语言/非必要 addons） | 10-15 | 繁琐 |
| 打包进 `jniLibs` 并打通 execve 路径 | 5-10 | **风险已排除，工作量小** |
| 运行时（proot 或 DSHBox 已有 `libproot.so` 复用） | 20-30 | 中等风险 |
| 显示传输（Xvfb 或 weston + Xwayland + VNC/RDP + 启动编排） | 20-30 | 中等 |
| 输入与中文输入法桥接（触摸→鼠标、软键盘→X11 keysym、IME） | 25-40 | 繁琐，决定体验成败 |
| Android 壳（启动器、前台服务、生命周期、存储、通知、电池优化豁免） | 15-20 | 繁琐 |
| 首启 provisioning（下载、校验、断点续传、解包、失败恢复） | 15-20 | 繁琐 |
| 打包签名与分发 | 5-10 | 简单 |
| 兼容性测试（多 OEM × Android 10-16 × 内存档位） | 40-60 | 极其繁琐 |
| **合计** | **170-260 人·天** | |

### 硬阻塞 vs 繁琐

**原判定为硬阻塞、现已排除**：targetSdk ≥ 29 无法 execve。已确证 `apk_data_file` 可执行。

**仍属硬阻塞或高风险**：

1. **CEF 与 ptrace 的兼容性**。若走 proot 路线，CEF 的 Chromium 多进程模型与 ptrace 拦截存在结构性冲突，可能导致白屏或崩溃。**缓解手段：直接裁掉 CEF**，因为核心离线编辑并不依赖它。裁掉后此风险基本消失。
2. **软件渲染的性能天花板**。`/dev/dri` 拒绝访问，只能 llvmpipe。1600x1000 下复杂表格重绘会明显掉帧。
3. **OEM 差异**。部分厂商杀后台激进、限制 `/data` 配额、SELinux 策略更严，决定"能装"与"能用"的差距。

**仅仅是繁琐**：rootfs 合成与瘦身、IME 桥接、首启下载、兼容性测试。

---

## 六、明确无法做到"全设备统一"的部分

必须诚实说明以下几点，它们与是否努力无关：

1. **arm64 限定**。载荷是 aarch64 ELF。32 位 ARM 设备与 x86 设备需要完全不同的二进制，不具备可行性。
2. **Android 版本下限**。需要 Android 10 以上（受 `apk_data_file` 执行授权与原生库机制约束），且需实测各版本行为。
3. **存储空间下限**。解包后 3.0 GB，加运行时开销，建议设备可用空间 ≥ 8 GB。低端机型无法承载。
4. **性能体验无法与原生 PC 相比**。纯软件渲染 + 容器层，属于"能用"而非"好用"。
5. **CEF 相关在线功能会缺失**（若选择裁剪以换取体积与稳定性）。
6. **中文输入法桥接是体验短板**。X11 的 keysym 与传统 IME 协议需要自行桥接，工作量与效果都取决于投入。

---

## 七、结论与建议

**结论：打包成可安装 APK 在技术上成立，且比上一轮评估的结论更乐观。**

关键变化在于：execve 路径不是死结，`apk_data_file` 本就是 AOSP 有意开放的。这意味着**不必锁 targetSdk 28，可以走正常上架流程**（但需首启下载）。

**建议的推进顺序**：

1. **先做最小可运行验证**：把精简后的 rootfs 打包成 `lib*.so` 放进 `jniLibs`，验证解包后 execve 与启动。这一步约 5-10 人天，却决定后续所有工作是否值得投入。
2. **同步裁定 CEF 取舍**。若放弃 CEF，体积减少 199 MiB 且绕开最大的稳定性风险。
3. **显示与输入选型**。建议 Xvfb + VNC 作为基线（依赖最少、最易调试），weston + Xwayland + RDP 作为体验优化项。
4. **再做 Android 壳与首启下载**。

### 需要提醒的风险

- 本次所有验证均在**当前这台设备**上完成。OEM 差异、Android 版本差异尚未覆盖，这是 40-60 人天测试工作量的由来。
- 把 2.5 GB 载荷塞进 `jniLibs` 属于非常规用法，需实测不同 OEM 的 PackageManager 行为与安装体积上限（置信度：中）。
- 软件渲染的性能表现需在真实业务场景下评估，实验室静态截图不能代表滚动、缩放、大表格重绘的体验。
