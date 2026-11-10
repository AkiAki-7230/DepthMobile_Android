# DepthMobile

DepthMobile 是一个在 Android 端离线运行的单目深度与行人辅助避障原型。当前主线已经恢复到 2026-08-14 本对话最后确认的 `YOLO26n-depth` 版本。

## 当前主线

- 模型：`YOLO26n-depth`，NCNN `384 × 384`。
- 相机：CameraX 分析帧优先使用 `384 × 288`，原生层按比例缩放并 LetterBox 到 `384 × 384`。
- 深度：原生 NCNN 推理输出稠密深度图；Kotlin 层执行时序平滑和异常保护。
- 避障：检测中央低矮障碍、近距离大面积正面墙体，并评估左/中/右区域用于提示方向。
- 提示：画面显示深度、等深线、危险区域、距离和 FPS；风险触发时播放滴声，危险级别同时振动。
- 运行：完全离线，当前 APK 仅打包 `arm64-v8a`。

当前构建不再启用 2026-08-19 另一轮实验加入的 `YOLO26s-depth 640`、硬件深度融合和重力三维占用模块。那一版已完整保存在 `recovery_backups/2026-08-24-other-chat-state/`，不会丢失。

## 主要代码

| 文件 | 作用 |
| --- | --- |
| `app/src/main/cpp/NativeNcnn.cpp` | 加载 YOLO26n-depth、图像预处理和 NCNN 推理 |
| `app/src/main/java/com/example/depthmobile/DepthAnalyzer.kt` | 相机帧转换、推理调度、平滑、检测与渲染编排 |
| `app/src/main/java/com/example/depthmobile/ObstacleDetector.kt` | 低矮障碍、正面墙体和左右通行区域判断 |
| `app/src/main/java/com/example/depthmobile/ObstacleAlerter.kt` | 滴声、方向音和振动提示 |
| `app/src/main/java/com/example/depthmobile/view/DepthOverlayView.kt` | 深度覆盖层、等深线、告警和 FPS 显示 |

## 跨设备米制校准

YOLO26n-depth 的 NCNN 图已经输出米制深度。App 在模型输出端另外提供一个全局残差比例，配置文件是：

`app/src/main/assets/depth_metric_calibration.properties`

默认 `correction_scale=1.0`，因此没有合格数据时不会改变模型原始结果。将来只允许使用多手机数据和整机留出验证生成新配置，用户端不需要执行任何校准。

标定 CSV 格式：

```csv
device_id,sample_id,predicted_m,ground_truth_m
phone_a,wall_050_001,0.56,0.50
phone_b,wall_050_001,0.53,0.50
```

运行开发工具：

```powershell
python tools/calibrate_depth_scale.py depth_samples.csv `
  --output app/src/main/assets/depth_metric_calibration.properties
```

工具使用与 Ultralytics 相同的 log-depth 全局比例思路，按设备进行留一验证；只有校准后的留出设备 `delta1` 提升且 `AbsRel` 不恶化时才输出新比例，否则继续输出恒等比例。

另有 `depth_calibration_tool.py` 与 `CalibrationActivity`（1/2/3/5 米四点采集）组成的单设备流程。该流程只做"按距离留一"验证，无法排除设备间差异，因此**只适合本机自查**：它写出的系数不要提交进发布配置。发布用的 `correction_scale` 仍必须由上面的多设备脚本生成。

旧 MATLAB 镜头参数没有记录对应手机身份，因此不会再自动应用到所有设备。只有在 `CameraCalibration.kt` 中补齐经过确认的厂商和型号后才会启用；未知设备直接使用 CameraX 原图，避免错误畸变参数破坏模型输入。

## 构建

在项目根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

### OpenCV 依赖（首次构建前必做）

仓库不包含 OpenCV 的预编译库（`sdk/native/libs`、`staticlibs`、`3rdparty`，合计约 510 MB）。
请先下载 **OpenCV 4.10.0 Android SDK**，再把它补齐到本仓库：

1. 从 opencv.org 的 releases 页下载 `opencv-4.10.0-android-sdk.zip`；
2. 解压，找到 `OpenCV-android-sdk/sdk/native/`；
3. 把其中的 `libs/`、`staticlibs/`、`3rdparty/` 三个目录复制到本仓库的 `sdk/native/` 下；
4. 目录结构应为 `sdk/native/libs/arm64-v8a/libopencv_java4.so` 等，然后即可执行上面的构建命令。

本项目只打包 `arm64-v8a`，如需精简，可只保留这三个目录下的 `arm64-v8a` 子目录（`sdk/build.gradle` 已设置 `abiFilters`）。

普通调试 APK 会写入 `app/build/outputs/apk/debug/app-debug.apk`，每次构建都可能覆盖它。当前跨设备校准开发版另外保存在：

`releases/DepthMobile-YOLO26n-depth-cross-device-calibration-20260825-debug.apk`

本次校准改动与验证记录见 `releases/CALIBRATION-20260825.md`；旧版恢复证据见 `releases/RECOVERY-20260824.md`。

## 使用边界

本项目面向行人的辅助避障，不是完整的道路导航系统，也不能替代视力、导盲杖、专业无障碍设备或人工判断。单目深度会受暗光、透明/反光物体、细杆、运动模糊、遮挡和模型域偏移影响；在楼梯、车流和复杂路口等高风险场景中必须保留其他安全手段。
