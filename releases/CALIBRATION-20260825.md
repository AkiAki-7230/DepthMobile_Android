# 2026-08-25 跨设备深度校准改动

## APK

- 文件：`DepthMobile-YOLO26n-depth-cross-device-calibration-20260825-debug.apk`
- 大小：88,704,773 字节
- SHA-256：`B84F19F0B60A0384F46A1754A17594F16C88FB805464FE917C6C4A14A79EE8B4`
- 签名：Android Debug，APK Signature Scheme v2 验证通过

## 已实现

1. 保留现有 `yolo26n_depth.bin/.param` 及其内置米制尺度。
2. 在 NCNN 输出返回 Kotlin 前增加线程安全的全局残差尺度修正。
3. 从 `assets/depth_metric_calibration.properties` 加载尺度，异常配置自动回退到 `1.0`。
4. 当前尺度为恒等值 `1.0`，所以在没有多设备标定数据时不会猜测或篡改模型距离。
5. 旧 MATLAB 镜头参数改为设备身份精确匹配后才启用；当前未记录原标定设备身份，因此未知手机使用 CameraX 原图。
6. 新增 `tools/calibrate_depth_scale.py`，使用设备平衡拟合和整机留一交叉验证生成配置。

## 验证

- Python 标定核心数值自测：通过。
- JVM 单元测试：3/3 通过。
- 离线干净构建 `:app:clean :app:testDebugUnitTest :app:assembleDebug`：通过。
- APK 内包含新配置、`yolo26n_depth` 三个模型文件和更新后的原生库。
- 原生库导出 `initModel`、`setDepthScaleCorrection`、`runDepth` 三个 JNI 符号。
- APK 中不存在 `yolo26s_depth_640`。

## 尚未完成的数据步骤

真正改变 `correction_scale` 前仍需要多款手机的 `predicted_m/ground_truth_m` 样本。工具会在未参与拟合的整台手机上比较原始和校准指标；只有留出设备 `delta1` 提升且 `AbsRel` 不恶化时才接受新比例。

由于构建时没有连接 Android 设备，本版本完成了编译、单元测试、模型/资产检查和签名验证，但尚未完成真机录像回归。
