#!/usr/bin/env python3
"""
深度标定工具（方案1）

从应用导出的 JSON 文件计算全局 correction_scale，并在写入配置前做
"留一距离" 交叉验证，只有确实改善时采用，否则保持恒等校正。

用法:
    python depth_calibration_tool.py calibration_20261025_143022.json

设计要点:
- 应用在采集期间会把原生校正系数复位为 1.0，并写入 applied_correction_scale。
  这里按该字段把测量值还原为模型原始输出，避免重复标定叠加系数。
- 本工具只做"按距离留一"验证，无法排除设备间差异，结果仅用于本机自查。
  发布用的 correction_scale 必须由 tools/calibrate_depth_scale.py 用多设备数据生成。
- 指标定义与 tools/calibrate_depth_scale.py 保持一致：
  delta1 = max(校正/真值, 真值/校正) < 1.25 的比例
  abs_rel = |校正-真值| / 真值
  rmse = sqrt(mean((校正-真值)^2))
"""

import json
import sys
from pathlib import Path
from typing import List, Sequence, Tuple

import numpy as np

MIN_ALLOWED_SCALE = 0.25
MAX_ALLOWED_SCALE = 4.0
MIN_SAMPLES = 3
MIN_DISTINCT_DISTANCES = 3
MAX_ACCEPTED_SPREAD = 0.12


def load_calibration_data(json_file: Path) -> Tuple[dict, np.ndarray, np.ndarray]:
    """加载 JSON，返回 (元数据, 目标距离, 还原后的模型原始深度)。"""
    with open(json_file, "r", encoding="utf-8-sig") as f:  # tolerate a BOM from Windows editors
        data = json.load(f)

    applied_scale = float(data.get("applied_correction_scale", 1.0))
    if not np.isfinite(applied_scale) or applied_scale <= 0:
        raise ValueError(f"applied_correction_scale 无效: {applied_scale}")

    samples = data.get("samples", [])
    if len(samples) < MIN_SAMPLES:
        raise ValueError(f"有效样本不足: {len(samples)}，至少需要 {MIN_SAMPLES} 个")

    targets: List[float] = []
    measured_raw: List[float] = []
    spreads: List[float] = []
    for sample in samples:
        target = float(sample["target_distance_m"])
        measured = float(sample["measured_depth_m"]) / applied_scale
        spread = float(sample.get("relative_spread", 0.0))
        if not (np.isfinite(target) and target > 0):
            continue
        if not (np.isfinite(measured) and measured > 0):
            continue
        targets.append(target)
        measured_raw.append(measured)
        spreads.append(spread)

    targets_array = np.array(targets, dtype=float)
    measured_array = np.array(measured_raw, dtype=float)

    distinct = np.unique(np.round(targets_array, 3))
    if len(distinct) < MIN_DISTINCT_DISTANCES:
        raise ValueError(
            f"距离点不足: {len(distinct)} 个，至少需要 {MIN_DISTINCT_DISTANCES} 个不同距离"
        )

    for target, spread in zip(targets_array, spreads):
        if spread > MAX_ACCEPTED_SPREAD:
            print(f"警告: {target:.1f}m 的样本波动 {spread * 100:.0f}%，可能影响结果")

    meta = {
        "device_manufacturer": data.get("device_manufacturer", "unknown"),
        "device_model": data.get("device_model", "unknown"),
        "timestamp": data.get("timestamp", "unknown"),
        "sample_count": len(targets_array),
        "applied_correction_scale": applied_scale,
    }
    return meta, targets_array, measured_array


def fit_scale(targets: np.ndarray, measured: np.ndarray) -> float:
    """按比例中位数拟合全局系数。"""
    ratios = targets / measured
    return float(np.median(ratios))


def calculate_metrics(targets: np.ndarray, measured: np.ndarray, scale: float) -> dict:
    calibrated = measured * scale
    ratios = np.maximum(calibrated / targets, targets / calibrated)
    relative_errors = np.abs(calibrated - targets) / targets
    return {
        "delta1": float(np.mean(ratios < 1.25)),
        "abs_rel": float(np.mean(relative_errors)),
        "rmse": float(np.sqrt(np.mean((calibrated - targets) ** 2))),
        "mae": float(np.mean(np.abs(calibrated - targets))),
    }


def macro_average(metrics: Sequence[dict]) -> dict:
    keys = ("delta1", "abs_rel", "rmse", "mae")
    return {key: float(np.mean([m[key] for m in metrics])) for key in keys}


def leave_one_distance_out(targets: np.ndarray, measured: np.ndarray) -> Tuple[dict, dict]:
    """
    留一距离交叉验证：用其余距离拟合系数，在留出距离上评估。
    返回 (原始指标, 校正后指标)，均为各留出组的宏平均。
    """
    raw_metrics: List[dict] = []
    calibrated_metrics: List[dict] = []
    distances = np.unique(np.round(targets, 3))

    for distance in distances:
        held_out = np.isclose(targets, distance)
        fit_mask = ~held_out
        if np.count_nonzero(fit_mask) < 2:
            continue
        scale = fit_scale(targets[fit_mask], measured[fit_mask])
        raw_metrics.append(calculate_metrics(targets[held_out], measured[held_out], 1.0))
        calibrated_metrics.append(
            calculate_metrics(targets[held_out], measured[held_out], scale)
        )

    if not raw_metrics:
        raise ValueError("留一距离验证失败：可用距离组合不足")
    return macro_average(raw_metrics), macro_average(calibrated_metrics)


def decide_scale(
    raw_cv: dict,
    calibrated_cv: dict,
    candidate: float,
) -> Tuple[float, str]:
    """只有校正后在留出距离上"不更差且至少一项更好"才采用。"""
    if abs(candidate - 1.0) < 1e-6:
        return 1.0, "identity_no_scale_needed"
    if not (MIN_ALLOWED_SCALE <= candidate <= MAX_ALLOWED_SCALE):
        return 1.0, "identity_scale_out_of_range"

    not_worse = (
        calibrated_cv["delta1"] >= raw_cv["delta1"] - 1e-9
        and calibrated_cv["abs_rel"] <= raw_cv["abs_rel"] + 1e-9
    )
    strictly_better = (
        calibrated_cv["delta1"] > raw_cv["delta1"] + 1e-9
        or calibrated_cv["abs_rel"] < raw_cv["abs_rel"] - 1e-9
    )
    if not_worse and strictly_better:
        return candidate, "manual_scale_distance_cv"
    return 1.0, "identity_distance_cv_rejected"


def print_report(
    meta: dict,
    targets: np.ndarray,
    measured: np.ndarray,
    candidate: float,
    selected: float,
    source: str,
    raw_cv: dict,
    calibrated_cv: dict,
) -> None:
    print("=" * 64)
    print("深度标定报告（方案1 + 留一距离验证）")
    print("=" * 64)
    print(f"\n设备: {meta['device_manufacturer']} {meta['device_model']}")
    print(f"采集时间: {meta['timestamp']}")
    print(f"采集时应用的校正系数: {meta['applied_correction_scale']:.4f}")
    print(f"有效样本: {meta['sample_count']}")

    print("\n样本详情:")
    print(f"{'目标(m)':<10}{'模型原始(m)':<14}{'比例':<10}{'帧数':<8}")
    print("-" * 46)
    for target, measured_value in zip(targets, measured):
        print(f"{target:<10.2f}{measured_value:<14.3f}{target / measured_value:<10.4f}")

    print(f"\n候选系数: {candidate:.6f}")

    print("\n留一距离交叉验证（各留出距离宏平均）:")
    print(f"{'指标':<10}{'原始':<12}{'校正后':<12}")
    print("-" * 36)
    for key in ("delta1", "abs_rel", "rmse", "mae"):
        print(f"{key:<10}{raw_cv[key]:<12.4f}{calibrated_cv[key]:<12.4f}")

    print(f"\n采纳结果: {source}")
    print(f"最终 correction_scale = {selected:.6f}")
    if selected == 1.0 and candidate != 1.0:
        print("说明: 留出验证未证明改善，保持恒等校正更安全。")
    print("=" * 64)


def update_config_file(
    correction_scale: float,
    source: str,
    sample_count: int,
) -> None:
    """保存本机标定结果；文件不存在时新建。"""
    config_file = Path("depth_calibration_result.properties")
    if config_file.exists():
        lines = config_file.read_text(encoding="utf-8").splitlines()
    else:
        lines = [
            "# 本机自查用标定结果，不会自动进入发布配置。",
            "# 发布用的 correction_scale 必须由 tools/calibrate_depth_scale.py 用多设备数据生成。",
        ]
    updates = {
        "correction_scale": f"{correction_scale:.6f}",
        "source": source,
        "sample_count": str(sample_count),
    }

    new_lines: List[str] = []
    seen = set()
    for line in lines:
        stripped = line.strip()
        if "=" in stripped and not stripped.startswith("#"):
            key = stripped.split("=", 1)[0].strip()
            if key in updates:
                new_lines.append(f"{key}={updates[key]}")
                seen.add(key)
                continue
        new_lines.append(line)

    for key, value in updates.items():
        if key not in seen:
            new_lines.append(f"{key}={value}")

    config_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    print(f"\n[OK] 已保存本机标定结果: {config_file}")
    print(f"  correction_scale = {correction_scale:.6f}")
    print(f"  source = {source}")


def main() -> None:
    if len(sys.argv) < 2:
        print("用法: python depth_calibration_tool.py <calibration.json>")
        sys.exit(1)

    json_file = Path(sys.argv[1])
    if not json_file.exists():
        print(f"错误: 文件不存在: {json_file}")
        sys.exit(1)

    try:
        meta, targets, measured = load_calibration_data(json_file)
    except (ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"错误: {error}")
        sys.exit(1)

    candidate = fit_scale(targets, measured)
    raw_cv, calibrated_cv = leave_one_distance_out(targets, measured)
    selected, source = decide_scale(raw_cv, calibrated_cv, candidate)

    print_report(
        meta,
        targets,
        measured,
        candidate,
        selected,
        source,
        raw_cv,
        calibrated_cv,
    )

    try:
        update_config_file(selected, source, len(targets))
    except (OSError, FileNotFoundError) as error:
        print(f"错误: {error}")
        sys.exit(1)

    print("\n下一步:")
    print("1. 重新编译并安装应用（.\\gradlew.bat :app:assembleDebug）")
    print("2. 在不参与标定的距离（如 1.5m / 4m）验证误差")
    print("3. 需要重新标定时直接再走一次流程，工具会先还原原始深度，不会叠加系数")


if __name__ == "__main__":
    main()
