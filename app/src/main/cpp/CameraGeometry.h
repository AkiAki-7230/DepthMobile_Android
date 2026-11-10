#pragma once

#include <opencv2/opencv.hpp>

namespace camera_geometry {

bool configure(
        int referenceWidth,
        int referenceHeight,
        float fx,
        float fy,
        float cx,
        float cy,
        const cv::Vec<float, 5>& distortion);

bool isConfigured();
bool hasCompatibleAspectRatio(int width, int height);
cv::Mat cameraMatrixForSize(int width, int height);
bool undistortRgbaInPlace(cv::Mat* rgba);
bool redistortRgba(const cv::Mat& undistorted, cv::Mat* distorted);
cv::Point2f distortUndistortedPoint(const cv::Point2f& point, int width, int height);
cv::Vec3f rayForUndistortedPixel(float x, float y, int width, int height, bool normalizeRay);

} // namespace camera_geometry
