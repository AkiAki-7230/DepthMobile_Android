#include "CameraGeometry.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace camera_geometry {
namespace {

struct CalibrationState {
    bool configured = false;
    int referenceWidth = 0;
    int referenceHeight = 0;
    float fx = 1.0f;
    float fy = 1.0f;
    float cx = 0.0f;
    float cy = 0.0f;
    cv::Vec<float, 5> distortion{};
};

CalibrationState calibration;
cv::Size cachedUndistortSize;
cv::Mat cachedUndistortMapX;
cv::Mat cachedUndistortMapY;
cv::Size cachedRedistortSize;
cv::Mat cachedRedistortMapX;
cv::Mat cachedRedistortMapY;

cv::Mat distortionMatrix() {
    return (cv::Mat_<double>(1, 5) <<
            calibration.distortion[0],
            calibration.distortion[1],
            calibration.distortion[2],
            calibration.distortion[3],
            calibration.distortion[4]);
}

void ensureUndistortMaps(const cv::Size& size) {
    if (cachedUndistortSize == size && !cachedUndistortMapX.empty()) return;
    const cv::Mat cameraMatrix = cameraMatrixForSize(size.width, size.height);
    cv::initUndistortRectifyMap(
            cameraMatrix,
            distortionMatrix(),
            cv::Mat(),
            cameraMatrix,
            size,
            CV_32FC1,
            cachedUndistortMapX,
            cachedUndistortMapY);
    cachedUndistortSize = size;
}

void ensureRedistortMaps(const cv::Size& size) {
    if (cachedRedistortSize == size && !cachedRedistortMapX.empty()) return;

    const cv::Mat cameraMatrix = cameraMatrixForSize(size.width, size.height);
    std::vector<cv::Point2f> distortedPixels;
    distortedPixels.reserve(size.area());
    for (int y = 0; y < size.height; ++y) {
        for (int x = 0; x < size.width; ++x) {
            distortedPixels.emplace_back(static_cast<float>(x), static_cast<float>(y));
        }
    }

    std::vector<cv::Point2f> undistortedPixels;
    cv::undistortPoints(
            distortedPixels,
            undistortedPixels,
            cameraMatrix,
            distortionMatrix(),
            cv::Mat(),
            cameraMatrix);

    cachedRedistortMapX.create(size, CV_32F);
    cachedRedistortMapY.create(size, CV_32F);
    size_t index = 0;
    for (int y = 0; y < size.height; ++y) {
        float* mapX = cachedRedistortMapX.ptr<float>(y);
        float* mapY = cachedRedistortMapY.ptr<float>(y);
        for (int x = 0; x < size.width; ++x, ++index) {
            mapX[x] = undistortedPixels[index].x;
            mapY[x] = undistortedPixels[index].y;
        }
    }
    cachedRedistortSize = size;
}

} // namespace

bool configure(
        int referenceWidth,
        int referenceHeight,
        float fx,
        float fy,
        float cx,
        float cy,
        const cv::Vec<float, 5>& distortion) {
    if (referenceWidth <= 0 || referenceHeight <= 0 ||
        !std::isfinite(fx) || !std::isfinite(fy) || fx <= 0.0f || fy <= 0.0f ||
        !std::isfinite(cx) || !std::isfinite(cy)) {
        return false;
    }
    calibration.configured = true;
    calibration.referenceWidth = referenceWidth;
    calibration.referenceHeight = referenceHeight;
    calibration.fx = fx;
    calibration.fy = fy;
    calibration.cx = cx;
    calibration.cy = cy;
    calibration.distortion = distortion;
    cachedUndistortSize = {};
    cachedRedistortSize = {};
    cachedUndistortMapX.release();
    cachedUndistortMapY.release();
    cachedRedistortMapX.release();
    cachedRedistortMapY.release();
    return true;
}

bool isConfigured() {
    return calibration.configured;
}

bool hasCompatibleAspectRatio(int width, int height) {
    if (!calibration.configured || width <= 0 || height <= 0) return false;
    const double referenceAspect =
            static_cast<double>(calibration.referenceWidth) / calibration.referenceHeight;
    const double actualAspect = static_cast<double>(width) / height;
    return std::abs(referenceAspect - actualAspect) <= referenceAspect * 0.015;
}

cv::Mat cameraMatrixForSize(int width, int height) {
    const double scaleX = static_cast<double>(width) / calibration.referenceWidth;
    const double scaleY = static_cast<double>(height) / calibration.referenceHeight;
    return (cv::Mat_<double>(3, 3) <<
            calibration.fx * scaleX, 0.0, calibration.cx * scaleX,
            0.0, calibration.fy * scaleY, calibration.cy * scaleY,
            0.0, 0.0, 1.0);
}

bool undistortRgbaInPlace(cv::Mat* rgba) {
    if (!calibration.configured || rgba == nullptr || rgba->empty() ||
        rgba->type() != CV_8UC4 ||
        !hasCompatibleAspectRatio(rgba->cols, rgba->rows)) {
        return false;
    }
    ensureUndistortMaps(rgba->size());
    cv::Mat corrected;
    cv::remap(
            *rgba,
            corrected,
            cachedUndistortMapX,
            cachedUndistortMapY,
            cv::INTER_LINEAR,
            cv::BORDER_REPLICATE);
    corrected.copyTo(*rgba);
    return true;
}

bool redistortRgba(const cv::Mat& undistorted, cv::Mat* distorted) {
    if (!calibration.configured || undistorted.empty() || distorted == nullptr) return false;
    ensureRedistortMaps(undistorted.size());
    cv::remap(
            undistorted,
            *distorted,
            cachedRedistortMapX,
            cachedRedistortMapY,
            cv::INTER_LINEAR,
            cv::BORDER_CONSTANT,
            cv::Scalar(0, 0, 0, 0));
    return true;
}

cv::Point2f distortUndistortedPoint(const cv::Point2f& point, int width, int height) {
    if (!calibration.configured || width <= 0 || height <= 0) return point;
    const double scaleX = static_cast<double>(width) / calibration.referenceWidth;
    const double scaleY = static_cast<double>(height) / calibration.referenceHeight;
    const double fx = calibration.fx * scaleX;
    const double fy = calibration.fy * scaleY;
    const double cx = calibration.cx * scaleX;
    const double cy = calibration.cy * scaleY;
    const double x = (point.x - cx) / fx;
    const double y = (point.y - cy) / fy;
    const double r2 = x * x + y * y;
    const double r4 = r2 * r2;
    const double r6 = r4 * r2;
    const double k1 = calibration.distortion[0];
    const double k2 = calibration.distortion[1];
    const double p1 = calibration.distortion[2];
    const double p2 = calibration.distortion[3];
    const double k3 = calibration.distortion[4];
    const double radial = 1.0 + k1 * r2 + k2 * r4 + k3 * r6;
    const double distortedX =
            x * radial + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x);
    const double distortedY =
            y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y;
    return {
            static_cast<float>(fx * distortedX + cx),
            static_cast<float>(fy * distortedY + cy)
    };
}

cv::Vec3f rayForUndistortedPixel(
        float x,
        float y,
        int width,
        int height,
        bool normalizeRay) {
    if (!calibration.configured || width <= 0 || height <= 0) return {0.0f, 0.0f, 1.0f};
    const float scaleX = static_cast<float>(width) / calibration.referenceWidth;
    const float scaleY = static_cast<float>(height) / calibration.referenceHeight;
    const float fx = calibration.fx * scaleX;
    const float fy = calibration.fy * scaleY;
    const float cx = calibration.cx * scaleX;
    const float cy = calibration.cy * scaleY;
    cv::Vec3f ray(
            (x - cx) / fx,
            (y - cy) / fy,
            1.0f);
    if (normalizeRay) {
        const float length = cv::norm(ray);
        if (length > 1e-6f) ray /= length;
    }
    return ray;
}

} // namespace camera_geometry
