#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <utility>
#include <vector>

#include "CameraGeometry.h"

namespace {

constexpr float kLowPercentile = 0.02f;
constexpr float kHighPercentile = 0.98f;
constexpr float kRangeTemporalAlpha = 0.34f;
constexpr int kBaseMinimumContourPoints = 10;
constexpr double kBaseMinimumContourArea = 18.0;
constexpr int kBaseMinimumDetailPoints = 7;
constexpr double kBaseMinimumDetailArea = 6.0;
constexpr size_t kMaximumOuterContoursPerLevel = 6;
constexpr size_t kMaximumFarDetailContoursPerLevel = 5;
constexpr size_t kMaximumNearDetailContoursPerLevel = 3;
constexpr int kMinimumLabelSeparation = 30;
constexpr size_t kMaximumDepthLabels = 4;
constexpr float kMinimumMetricLabelGap = 0.06f;
constexpr float kFarContourGamma = 1.20f;

bool stableRangeInitialized = false;
float stableLow = 0.0f;
float stableHigh = 1.0f;

struct Rgba {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
    uint8_t alpha;
};

struct StructuralCandidate {
    std::vector<cv::Point> contour;
    cv::Rect bounds;
    double score;
    float normalizedDepth;
};

enum class PlaneKind {
    WALL,
    HORIZONTAL,
    OTHER
};

struct PlaneEstimate {
    cv::Vec3f coefficients;
    PlaneKind kind;
    float confidence;
};

struct PlaneSample {
    cv::Vec3f ray;
    float inverseDepth;
};

float clamp01(float value) {
    return std::max(0.0f, std::min(1.0f, value));
}

float percentile(const std::vector<float>& sortedValues, float ratio) {
    const float position = clamp01(ratio) * static_cast<float>(sortedValues.size() - 1);
    const size_t left = static_cast<size_t>(position);
    const size_t right = std::min(left + 1, sortedValues.size() - 1);
    const float fraction = position - static_cast<float>(left);
    return sortedValues[left] + (sortedValues[right] - sortedValues[left]) * fraction;
}

Rgba depthColor(float normalizedDepth, uint8_t alpha) {
    struct Stop { float position; Rgba color; };
    static constexpr Stop stops[] = {
            {0.00f, {94, 0, 172, 255}},
            {0.18f, {43, 55, 255, 255}},
            {0.36f, {0, 196, 255, 255}},
            {0.54f, {0, 231, 104, 255}},
            {0.70f, {228, 255, 0, 255}},
            {0.85f, {255, 179, 0, 255}},
            {1.00f, {244, 48, 20, 255}}
    };
    constexpr size_t stopCount = sizeof(stops) / sizeof(stops[0]);

    const float value = clamp01(normalizedDepth);
    for (size_t index = 1; index < stopCount; ++index) {
        if (value <= stops[index].position) {
            const Stop& from = stops[index - 1];
            const Stop& to = stops[index];
            const float amount = (value - from.position) / (to.position - from.position);
            return {
                    static_cast<uint8_t>(from.color.red + (to.color.red - from.color.red) * amount),
                    static_cast<uint8_t>(from.color.green + (to.color.green - from.color.green) * amount),
                    static_cast<uint8_t>(from.color.blue + (to.color.blue - from.color.blue) * amount),
                    alpha
            };
        }
    }
    const Rgba last = stops[stopCount - 1].color;
    return {last.red, last.green, last.blue, alpha};
}

bool normalizeDepth(
        JNIEnv* env,
        jfloatArray depthArray,
        int width,
        int height,
        bool updateTemporalRange,
        cv::Mat* normalized,
        cv::Mat* metricDepth = nullptr) {
    if (depthArray == nullptr || normalized == nullptr || width <= 0 || height <= 0) return false;

    const jlong expectedSamples = static_cast<jlong>(width) * height;
    if (expectedSamples <= 0 || expectedSamples > env->GetArrayLength(depthArray)) return false;

    const int sampleCount = static_cast<int>(expectedSamples);
    std::vector<float> depth(sampleCount);
    env->GetFloatArrayRegion(depthArray, 0, sampleCount, depth.data());

    std::vector<float> finiteDepth;
    finiteDepth.reserve(depth.size());
    for (const float value : depth) {
        if (std::isfinite(value)) finiteDepth.push_back(value);
    }
    if (finiteDepth.size() < static_cast<size_t>(sampleCount / 2)) return false;

    std::sort(finiteDepth.begin(), finiteDepth.end());
    const float rawLow = percentile(finiteDepth, kLowPercentile);
    const float rawHigh = percentile(finiteDepth, kHighPercentile);
    const float fallbackDepth = percentile(finiteDepth, 0.50f);
    if (!std::isfinite(rawLow) || !std::isfinite(rawHigh) || rawHigh - rawLow < 1e-5f) return false;
    for (float& value : depth) {
        if (!std::isfinite(value)) value = fallbackDepth;
    }

    const cv::Mat rawDepth(height, width, CV_32F, depth.data());
    cv::Mat edgePreserved;
    const double sigmaColor = std::max(1e-4, static_cast<double>(rawHigh - rawLow) * 0.06);
    cv::bilateralFilter(rawDepth, edgePreserved, 7, sigmaColor, 3.0, cv::BORDER_REPLICATE);
    cv::Mat smoothedDepth;
    cv::GaussianBlur(edgePreserved, smoothedDepth, cv::Size(3, 3), 0.0, 0.0, cv::BORDER_REPLICATE);
    if (metricDepth != nullptr) {
        smoothedDepth.copyTo(*metricDepth);
    }

    std::vector<float> smoothValues(smoothedDepth.begin<float>(), smoothedDepth.end<float>());
    std::sort(smoothValues.begin(), smoothValues.end());
    const float currentLow = percentile(smoothValues, kLowPercentile);
    const float currentHigh = percentile(smoothValues, kHighPercentile);

    if (updateTemporalRange) {
        if (!stableRangeInitialized) {
            stableLow = currentLow;
            stableHigh = currentHigh;
            stableRangeInitialized = true;
        } else {
            stableLow += (currentLow - stableLow) * kRangeTemporalAlpha;
            stableHigh += (currentHigh - stableHigh) * kRangeTemporalAlpha;
        }
    }
    const float low = stableRangeInitialized ? stableLow : currentLow;
    const float high = stableRangeInitialized ? stableHigh : currentHigh;
    if (!std::isfinite(low) || !std::isfinite(high) || high - low < 1e-5f) return false;

    normalized->create(height, width, CV_32F);
    for (int y = 0; y < height; ++y) {
        const float* source = smoothedDepth.ptr<float>(y);
        float* destination = normalized->ptr<float>(y);
        for (int x = 0; x < width; ++x) {
            // Colour and contour levels represent nearness: 1=near/red, 0=far/purple.
            // This keeps the map consistent with the on-screen legend and plane fitter.
            destination[x] = 1.0f - clamp01((source[x] - low) / (high - low));
        }
    }
    return true;
}

bool isFullFrameContour(const std::vector<cv::Point>& contour, int width, int height) {
    const cv::Rect bounds = cv::boundingRect(contour);
    return bounds.x <= 1 && bounds.y <= 1 &&
           bounds.x + bounds.width >= width - 1 &&
           bounds.y + bounds.height >= height - 1;
}

float contourLevel(int levelIndex, int levelCount) {
    // Monocular inverse-depth values are compressed in the far field. A mild power curve adds
    // samples there while retaining several warm near-field layers around large objects.
    const float uniformPosition =
            static_cast<float>(levelIndex + 1) / static_cast<float>(levelCount + 1);
    return std::pow(uniformPosition, kFarContourGamma);
}

PlaneKind classifyPlane(const cv::Vec3f& coefficients) {
    const float length = cv::norm(coefficients);
    if (length < 1e-6f) return PlaneKind::OTHER;
    const cv::Vec3f normal = coefficients / length;
    if (std::abs(normal[2]) >= 0.78f) return PlaneKind::WALL;
    if (std::abs(normal[1]) >= 0.68f) return PlaneKind::HORIZONTAL;
    return PlaneKind::OTHER;
}

std::vector<PlaneEstimate> fitDominantPlanes(const cv::Mat& normalizedDepth) {
    std::vector<PlaneEstimate> estimates;
    if (!camera_geometry::isConfigured() || normalizedDepth.empty()) return estimates;

    std::vector<PlaneSample> samples;
    for (int y = 1; y < normalizedDepth.rows - 1; y += 2) {
        const float* row = normalizedDepth.ptr<float>(y);
        for (int x = 1; x < normalizedDepth.cols - 1; x += 2) {
            const float value = row[x];
            if (!std::isfinite(value) || value <= 0.01f || value >= 0.99f) continue;
            samples.push_back({
                    camera_geometry::rayForUndistortedPixel(
                            static_cast<float>(x),
                            static_cast<float>(y),
                            normalizedDepth.cols,
                            normalizedDepth.rows,
                            false),
                    value
            });
        }
    }
    if (samples.size() < 300) return estimates;

    std::vector<int> remaining(samples.size());
    for (size_t index = 0; index < remaining.size(); ++index) {
        remaining[index] = static_cast<int>(index);
    }
    const size_t originalSampleCount = samples.size();
    cv::RNG random(0x4d595df4);

    for (int planeIndex = 0; planeIndex < 3 && remaining.size() >= 180; ++planeIndex) {
        cv::Vec3f bestCoefficients;
        std::vector<int> bestInliers;
        for (int iteration = 0; iteration < 90; ++iteration) {
            const int firstIndex = remaining[random.uniform(0, static_cast<int>(remaining.size()))];
            const int secondIndex = remaining[random.uniform(0, static_cast<int>(remaining.size()))];
            const int thirdIndex = remaining[random.uniform(0, static_cast<int>(remaining.size()))];
            if (firstIndex == secondIndex || firstIndex == thirdIndex || secondIndex == thirdIndex) {
                continue;
            }

            const PlaneSample& first = samples[firstIndex];
            const PlaneSample& second = samples[secondIndex];
            const PlaneSample& third = samples[thirdIndex];
            const cv::Matx33f system(
                    first.ray[0], first.ray[1], first.ray[2],
                    second.ray[0], second.ray[1], second.ray[2],
                    third.ray[0], third.ray[1], third.ray[2]);
            if (std::abs(cv::determinant(system)) < 1e-5) continue;
            const cv::Vec3f candidate = system.inv() * cv::Vec3f(
                    first.inverseDepth, second.inverseDepth, third.inverseDepth);

            std::vector<int> inliers;
            inliers.reserve(remaining.size());
            for (const int sampleIndex : remaining) {
                const PlaneSample& sample = samples[sampleIndex];
                if (std::abs(sample.ray.dot(candidate) - sample.inverseDepth) <= 0.018f) {
                    inliers.push_back(sampleIndex);
                }
            }
            if (inliers.size() > bestInliers.size()) {
                bestCoefficients = candidate;
                bestInliers = std::move(inliers);
            }
        }

        const size_t minimumInliers = std::max<size_t>(180, remaining.size() / 14);
        if (bestInliers.size() < minimumInliers) break;

        cv::Matx33f normalMatrix = cv::Matx33f::zeros();
        cv::Vec3f rightHandSide(0.0f, 0.0f, 0.0f);
        for (const int sampleIndex : bestInliers) {
            const PlaneSample& sample = samples[sampleIndex];
            for (int row = 0; row < 3; ++row) {
                rightHandSide[row] += sample.ray[row] * sample.inverseDepth;
                for (int column = 0; column < 3; ++column) {
                    normalMatrix(row, column) += sample.ray[row] * sample.ray[column];
                }
            }
        }
        cv::Vec3f refined;
        if (!cv::solve(normalMatrix, rightHandSide, refined, cv::DECOMP_SVD)) {
            refined = bestCoefficients;
        }

        estimates.push_back({
                refined,
                classifyPlane(refined),
                static_cast<float>(bestInliers.size()) /
                    static_cast<float>(originalSampleCount)
        });

        std::vector<int> nextRemaining;
        nextRemaining.reserve(remaining.size() - bestInliers.size());
        for (const int sampleIndex : remaining) {
            const PlaneSample& sample = samples[sampleIndex];
            if (std::abs(sample.ray.dot(refined) - sample.inverseDepth) > 0.022f) {
                nextRemaining.push_back(sampleIndex);
            }
        }
        remaining.swap(nextRemaining);
    }
    return estimates;
}

cv::Mat reflectiveHorizontalPlaneMask(
        const cv::Mat& renderDepth,
        const cv::Mat& rgbEdges,
        const cv::Mat& depthGradient,
        const std::vector<PlaneEstimate>& planes) {
    cv::Mat reflectiveMask(renderDepth.size(), CV_8U, cv::Scalar(0));
    const double imageArea = static_cast<double>(renderDepth.total());

    for (const PlaneEstimate& plane : planes) {
        if (plane.kind != PlaneKind::HORIZONTAL || plane.confidence < 0.07f) continue;

        cv::Mat planeMask(renderDepth.size(), CV_8U, cv::Scalar(0));
        int planePixels = 0;
        int rgbEdgePixels = 0;
        int depthSupportedEdgePixels = 0;
        for (int y = 0; y < renderDepth.rows; ++y) {
            const float* depthRow = renderDepth.ptr<float>(y);
            const uint8_t* edgeRow = rgbEdges.ptr<uint8_t>(y);
            const float* gradientRow = depthGradient.ptr<float>(y);
            uint8_t* maskRow = planeMask.ptr<uint8_t>(y);
            for (int x = 0; x < renderDepth.cols; ++x) {
                const cv::Vec3f ray = camera_geometry::rayForUndistortedPixel(
                        static_cast<float>(x),
                        static_cast<float>(y),
                        renderDepth.cols,
                        renderDepth.rows,
                        false);
                if (std::abs(ray.dot(plane.coefficients) - depthRow[x]) > 0.024f) continue;
                maskRow[x] = 255;
                ++planePixels;
                if (edgeRow[x] != 0) {
                    ++rgbEdgePixels;
                    if (gradientRow[x] >= 0.018f) ++depthSupportedEdgePixels;
                }
            }
        }

        if (planePixels < imageArea * 0.07) continue;
        const double rgbEdgeDensity = static_cast<double>(rgbEdgePixels) / planePixels;
        const double supportedRatio = static_cast<double>(depthSupportedEdgePixels) /
                std::max(1, rgbEdgePixels);
        // A large horizontal depth plane containing many appearance edges without matching
        // geometric edges is the characteristic signal of a glass/reflective tabletop.
        if (rgbEdgeDensity >= 0.012 && supportedRatio <= 0.42) {
            const cv::Mat kernel = cv::getStructuringElement(
                    cv::MORPH_ELLIPSE, cv::Size(7, 7));
            cv::morphologyEx(planeMask, planeMask, cv::MORPH_CLOSE, kernel);
            cv::bitwise_or(reflectiveMask, planeMask, reflectiveMask);
        }
    }
    return reflectiveMask;
}

std::vector<std::vector<cv::Point>> contoursAtLevel(
        const cv::Mat& normalized,
        float level,
        int renderScale) {
    cv::Mat mask;
    cv::compare(normalized, level, mask, cv::CMP_GE);
    const cv::Mat kernel = cv::getStructuringElement(
            cv::MORPH_ELLIPSE,
            cv::Size(3, 3)
    );
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);

    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    // CCOMP retains bounded holes inside a large wall/table component. Those holes are often
    // real small objects or far-field structures; they are filtered separately below.
    cv::findContours(mask, contours, hierarchy, cv::RETR_CCOMP, cv::CHAIN_APPROX_NONE);

    const double scaleArea = static_cast<double>(renderScale * renderScale);
    const float distanceFactor = 0.58f + 0.42f * clamp01(level);
    const int minimumOuterPoints = static_cast<int>(
            std::round(kBaseMinimumContourPoints * renderScale * distanceFactor));
    const double minimumOuterArea = kBaseMinimumContourArea * scaleArea * distanceFactor;
    const int minimumDetailPoints = static_cast<int>(
            std::round(kBaseMinimumDetailPoints * renderScale * distanceFactor));
    const double minimumDetailArea = kBaseMinimumDetailArea * scaleArea * distanceFactor;
    const double maximumDetailArea = normalized.total() * (level < 0.45f ? 0.20 : 0.12);

    std::vector<std::vector<cv::Point>> outerContours;
    std::vector<std::vector<cv::Point>> detailContours;
    outerContours.reserve(contours.size());
    detailContours.reserve(contours.size());
    for (size_t index = 0; index < contours.size(); ++index) {
        std::vector<cv::Point>& contour = contours[index];
        if (isFullFrameContour(contour, normalized.cols, normalized.rows)) continue;

        const double area = std::abs(cv::contourArea(contour));
        const bool isInternal = !hierarchy.empty() && hierarchy[index][3] >= 0;
        if (!isInternal) {
            if (static_cast<int>(contour.size()) >= minimumOuterPoints &&
                area >= minimumOuterArea) {
                outerContours.push_back(std::move(contour));
            }
            continue;
        }

        const cv::Rect bounds = cv::boundingRect(contour);
        const int minimumDimension = std::max(2, renderScale);
        if (static_cast<int>(contour.size()) >= minimumDetailPoints &&
            area >= minimumDetailArea &&
            area <= maximumDetailArea &&
            bounds.width >= minimumDimension &&
            bounds.height >= minimumDimension) {
            detailContours.push_back(std::move(contour));
        }
    }

    const auto largestFirst =
              [](const std::vector<cv::Point>& first, const std::vector<cv::Point>& second) {
                  return cv::contourArea(first) > cv::contourArea(second);
              };
    std::sort(outerContours.begin(), outerContours.end(), largestFirst);
    std::sort(detailContours.begin(), detailContours.end(), largestFirst);
    if (outerContours.size() > kMaximumOuterContoursPerLevel) {
        outerContours.resize(kMaximumOuterContoursPerLevel);
    }
    const size_t detailLimit = level < 0.45f
            ? kMaximumFarDetailContoursPerLevel
            : kMaximumNearDetailContoursPerLevel;
    if (detailContours.size() > detailLimit) detailContours.resize(detailLimit);

    std::vector<std::vector<cv::Point>> visible;
    visible.reserve(outerContours.size() + detailContours.size());
    for (std::vector<cv::Point>& contour : outerContours) {
        visible.push_back(std::move(contour));
    }
    for (std::vector<cv::Point>& contour : detailContours) {
        visible.push_back(std::move(contour));
    }
    return visible;
}

cv::Mat upsampleForRendering(const cv::Mat& normalized, int renderScale) {
    if (renderScale <= 1) return normalized;
    cv::Mat upsampled;
    cv::resize(
            normalized,
            upsampled,
            cv::Size(normalized.cols * renderScale, normalized.rows * renderScale),
            0.0,
            0.0,
            cv::INTER_CUBIC
    );
    cv::min(upsampled, 1.0, upsampled);
    cv::max(upsampled, 0.0, upsampled);
    return upsampled;
}

bool bitmapToGuideGray(
        JNIEnv* env,
        jobject bitmap,
        const cv::Size& targetSize,
        cv::Mat* guideGray) {
    if (bitmap == nullptr || guideGray == nullptr) return false;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.width == 0 || info.height == 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        return false;
    }

    cv::Mat sourceGray;
    try {
        const cv::Mat sourceRgba(
                static_cast<int>(info.height),
                static_cast<int>(info.width),
                CV_8UC4,
                pixels,
                static_cast<size_t>(info.stride));
        cv::cvtColor(sourceRgba, sourceGray, cv::COLOR_RGBA2GRAY);
    } catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    cv::resize(sourceGray, *guideGray, targetSize, 0.0, 0.0, cv::INTER_AREA);
    return !guideGray->empty();
}

double intersectionOverUnion(const cv::Rect& first, const cv::Rect& second) {
    const cv::Rect intersection = first & second;
    if (intersection.empty()) return 0.0;
    const double intersectionArea = static_cast<double>(intersection.area());
    const double unionArea = static_cast<double>(first.area() + second.area()) - intersectionArea;
    return unionArea > 0.0 ? intersectionArea / unionArea : 0.0;
}

bool isCompactRightAngleTarget(
        const std::vector<cv::Point>& polygon,
        double aspectRatio,
        double extent,
        double solidity,
        double areaRatio) {
    if (polygon.size() != 4 || areaRatio > 0.006 ||
        aspectRatio < 0.35 || aspectRatio > 2.8 ||
        extent < 0.72 || solidity < 0.90) {
        return false;
    }

    for (size_t index = 0; index < polygon.size(); ++index) {
        const cv::Point2f previous = polygon[(index + polygon.size() - 1) % polygon.size()];
        const cv::Point2f current = polygon[index];
        const cv::Point2f next = polygon[(index + 1) % polygon.size()];
        const cv::Point2f first = previous - current;
        const cv::Point2f second = next - current;
        const double denominator = cv::norm(first) * cv::norm(second);
        if (denominator < 1e-4) return false;
        const double cosine = std::abs(first.dot(second) / denominator);
        if (cosine > 0.36) return false;
    }
    return true;
}

void drawStructuralDetails(
        cv::Mat* rgba,
        const cv::Mat& renderDepth,
        const cv::Mat& guideGray,
        const std::vector<PlaneEstimate>& planes,
        uint8_t alpha,
        int lineThickness,
        int maximumDetails) {
    if (rgba == nullptr || rgba->empty() || renderDepth.empty() || guideGray.empty() ||
        alpha == 0 || maximumDetails <= 0) {
        return;
    }

    cv::Mat smoothedGray;
    cv::bilateralFilter(guideGray, smoothedGray, 5, 28.0, 3.0, cv::BORDER_REPLICATE);

    // Derive Canny thresholds from the current scene, but keep a floor so flat walls do not turn
    // sensor noise and paint texture into contours.
    cv::Mat gradientX;
    cv::Mat gradientY;
    cv::Mat gradientMagnitude;
    cv::Sobel(smoothedGray, gradientX, CV_32F, 1, 0, 3);
    cv::Sobel(smoothedGray, gradientY, CV_32F, 0, 1, 3);
    cv::magnitude(gradientX, gradientY, gradientMagnitude);
    std::vector<float> gradients(
            gradientMagnitude.begin<float>(), gradientMagnitude.end<float>());
    std::sort(gradients.begin(), gradients.end());
    const double highThreshold = std::max(
            42.0,
            std::min(115.0, static_cast<double>(percentile(gradients, 0.88f))));
    const double lowThreshold = highThreshold * 0.38;

    cv::Mat edges;
    cv::Canny(smoothedGray, edges, lowThreshold, highThreshold, 3, true);
    const cv::Mat closeKernel = cv::getStructuringElement(
            cv::MORPH_RECT, cv::Size(3, 3));
    cv::morphologyEx(edges, edges, cv::MORPH_CLOSE, closeKernel);

    cv::Mat depthGradientX;
    cv::Mat depthGradientY;
    cv::Mat depthGradientMagnitude;
    cv::Sobel(renderDepth, depthGradientX, CV_32F, 1, 0, 3);
    cv::Sobel(renderDepth, depthGradientY, CV_32F, 0, 1, 3);
    cv::magnitude(depthGradientX, depthGradientY, depthGradientMagnitude);
    const cv::Mat reflectivePlaneMask = reflectiveHorizontalPlaneMask(
            renderDepth, edges, depthGradientMagnitude, planes);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    const double imageArea = static_cast<double>(edges.total());
    const double minimumArea = imageArea * 0.00012;
    const double maximumArea = imageArea * 0.025;
    std::vector<StructuralCandidate> candidates;
    candidates.reserve(contours.size());

    for (const std::vector<cv::Point>& contour : contours) {
        const double perimeter = cv::arcLength(contour, true);
        if (perimeter < 18.0) continue;

        std::vector<cv::Point> simplified;
        cv::approxPolyDP(contour, simplified, perimeter * 0.018, true);
        if (simplified.size() < 4 || simplified.size() > 12) continue;

        const double area = std::abs(cv::contourArea(simplified));
        if (area < minimumArea || area > maximumArea) continue;

        const cv::Rect bounds = cv::boundingRect(simplified);
        if (bounds.width < 8 || bounds.height < 6 ||
            bounds.x <= 2 || bounds.y <= 2 ||
            bounds.br().x >= edges.cols - 2 || bounds.br().y >= edges.rows - 2) {
            continue;
        }

        const double aspectRatio = static_cast<double>(bounds.width) / bounds.height;
        const double extent = area / std::max(1.0, static_cast<double>(bounds.area()));
        if (aspectRatio < 0.25 || aspectRatio > 4.0 || extent < 0.42) continue;

        std::vector<cv::Point> hull;
        cv::convexHull(simplified, hull);
        const double hullArea = std::abs(cv::contourArea(hull));
        const double solidity = hullArea > 1.0 ? area / hullArea : 0.0;
        if (solidity < 0.76) continue;

        cv::Scalar depthMean;
        cv::Scalar depthStandardDeviation;
        cv::meanStdDev(renderDepth(bounds), depthMean, depthStandardDeviation);
        if (depthStandardDeviation[0] > 0.085) continue;

        double gradientSum = 0.0;
        int gradientSupportCount = 0;
        for (const cv::Point& point : contour) {
            float localMaximum = 0.0f;
            for (int offsetY = -1; offsetY <= 1; ++offsetY) {
                const int sampleY = std::max(
                        0, std::min(depthGradientMagnitude.rows - 1, point.y + offsetY));
                for (int offsetX = -1; offsetX <= 1; ++offsetX) {
                    const int sampleX = std::max(
                            0, std::min(depthGradientMagnitude.cols - 1, point.x + offsetX));
                    localMaximum = std::max(
                            localMaximum,
                            depthGradientMagnitude.at<float>(sampleY, sampleX));
                }
            }
            gradientSum += localMaximum;
            if (localMaximum >= 0.018f) ++gradientSupportCount;
        }
        const double meanDepthGradient = gradientSum / std::max<size_t>(1, contour.size());
        const double depthSupportRatio =
                static_cast<double>(gradientSupportCount) / std::max<size_t>(1, contour.size());
        const bool hasDepthSupport =
                meanDepthGradient >= 0.012 && depthSupportRatio >= 0.18;
        const double areaRatio = area / imageArea;
        const bool isSmallManMadeTarget = isCompactRightAngleTarget(
                simplified, aspectRatio, extent, solidity, areaRatio);
        // Reflections on glass have strong RGB edges but usually no corresponding physical depth
        // boundary. Only a compact right-angled target (for example a wall switch) may bypass
        // depth support, and even that exception is restricted to a very small image area.
        if (!hasDepthSupport && !isSmallManMadeTarget) continue;

        if (!reflectivePlaneMask.empty()) {
            const double reflectiveOverlap = static_cast<double>(
                    cv::countNonZero(reflectivePlaneMask(bounds))) /
                    std::max(1.0, static_cast<double>(bounds.area()));
            const bool hasStrongPhysicalBoundary =
                    meanDepthGradient >= 0.028 && depthSupportRatio >= 0.40;
            if (reflectiveOverlap >= 0.32 && !hasStrongPhysicalBoundary) continue;
        }

        const double flatnessScore = 1.0 - std::min(1.0, depthStandardDeviation[0] / 0.085);
        const double supportScore = hasDepthSupport
                ? std::min(1.0, meanDepthGradient / 0.04)
                : 0.55;
        candidates.push_back({
                std::move(simplified),
                bounds,
                area * (0.70 + 0.30 * solidity) *
                    (0.75 + 0.25 * flatnessScore) * (0.70 + 0.30 * supportScore),
                clamp01(static_cast<float>(depthMean[0]))
        });
    }

    std::sort(candidates.begin(), candidates.end(),
              [](const StructuralCandidate& first, const StructuralCandidate& second) {
                  return first.score > second.score;
              });

    std::vector<cv::Rect> acceptedBounds;
    int drawn = 0;
    for (const StructuralCandidate& candidate : candidates) {
        bool duplicate = false;
        for (const cv::Rect& accepted : acceptedBounds) {
            if (intersectionOverUnion(candidate.bounds, accepted) > 0.55) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) continue;

        const Rgba color = depthColor(candidate.normalizedDepth, alpha);
        cv::polylines(
                *rgba,
                candidate.contour,
                true,
                cv::Scalar(color.red, color.green, color.blue, color.alpha),
                lineThickness,
                cv::LINE_AA);
        acceptedBounds.push_back(candidate.bounds);
        if (++drawn >= maximumDetails) break;
    }
}

jbyteArray transparentContour(JNIEnv* env, int width, int height) {
    const jsize size = static_cast<jsize>(width * height * 4);
    std::vector<jbyte> transparent(size, 0);
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, size, transparent.data());
    return result;
}

} // namespace

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_depthmobile_NativeContour_generateContour(
        JNIEnv* env,
        jobject,
        jfloatArray depthArray,
        jint width,
        jint height,
        jint levels,
        jint lineOpacity,
        jint requestedLineThickness,
        jint requestedRenderScale,
        jobject guideBitmap,
        jint structuralDetailOpacity,
        jint maximumStructuralDetails) {
    if (width <= 0 || height <= 0 || levels < 2) return nullptr;
    const int renderScale = std::max(1, std::min(4, static_cast<int>(requestedRenderScale)));
    const int lineThickness = std::max(
            1, std::min(3, static_cast<int>(requestedLineThickness)));
    const int renderWidth = width * renderScale;
    const int renderHeight = height * renderScale;

    cv::Mat normalized;
    if (!normalizeDepth(env, depthArray, width, height, true, &normalized)) {
        return transparentContour(env, renderWidth, renderHeight);
    }
    const cv::Mat renderDepth = upsampleForRendering(normalized, renderScale);

    const uint8_t alpha = static_cast<uint8_t>(
            std::max(0, std::min(255, static_cast<int>(lineOpacity)))
    );
    cv::Mat rgba(renderHeight, renderWidth, CV_8UC4, cv::Scalar(0, 0, 0, 0));
    for (int levelIndex = 0; levelIndex < levels; ++levelIndex) {
        const float level = contourLevel(levelIndex, levels);
        const std::vector<std::vector<cv::Point>> contours =
                contoursAtLevel(renderDepth, level, renderScale);
        if (!contours.empty()) {
            const Rgba color = depthColor(level, alpha);
            cv::drawContours(
                    rgba,
                    contours,
                    -1,
                    cv::Scalar(color.red, color.green, color.blue, color.alpha),
                    lineThickness,
                    cv::LINE_AA
            );
        }
    }

    const uint8_t detailAlpha = static_cast<uint8_t>(
            std::max(0, std::min(255, static_cast<int>(structuralDetailOpacity))));
    const int detailLimit = std::max(
            0, std::min(12, static_cast<int>(maximumStructuralDetails)));
    if (detailAlpha > 0 && detailLimit > 0) {
        const std::vector<PlaneEstimate> planes = fitDominantPlanes(normalized);
        cv::Mat guideGray;
        if (bitmapToGuideGray(
                    env,
                    guideBitmap,
                    cv::Size(renderWidth, renderHeight),
                    &guideGray)) {
            drawStructuralDetails(
                    &rgba,
                    renderDepth,
                    guideGray,
                    planes,
                    detailAlpha,
                    lineThickness,
                    detailLimit);
        }
    }

    cv::Mat displayRgba;
    const cv::Mat* outputRgba = &rgba;
    if (camera_geometry::redistortRgba(rgba, &displayRgba)) {
        outputRgba = &displayRgba;
    }

    const jsize size = static_cast<jsize>(renderWidth * renderHeight * 4);
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) {
        env->SetByteArrayRegion(
                result,
                0,
                size,
                reinterpret_cast<const jbyte*>(outputRgba->data));
    }
    return result;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_depthmobile_NativeContour_generateContourLabels(
        JNIEnv* env,
        jobject,
        jfloatArray depthArray,
        jint width,
        jint height,
        jint levels,
        jint requestedRenderScale) {
    jfloatArray empty = env->NewFloatArray(0);
    if (width <= 0 || height <= 0 || levels < 2) return empty;
    const int renderScale = std::max(1, std::min(4, static_cast<int>(requestedRenderScale)));

    cv::Mat normalized;
    cv::Mat metricDepth;
    if (!normalizeDepth(env, depthArray, width, height, false, &normalized, &metricDepth)) return empty;
    const cv::Mat renderDepth = upsampleForRendering(normalized, renderScale);
    const cv::Mat renderMetricDepth = upsampleForRendering(metricDepth, renderScale);

    std::vector<float> labels;
    std::vector<cv::Point> labelPositions;
    std::vector<float> metricLabelDepths;
    const int minimumSeparation = kMinimumLabelSeparation * renderScale;
    for (int levelIndex = 0; levelIndex < levels; ++levelIndex) {
        if (metricLabelDepths.size() >= kMaximumDepthLabels) break;
        const float level = contourLevel(levelIndex, levels);
        const std::vector<std::vector<cv::Point>> contours =
                contoursAtLevel(renderDepth, level, renderScale);
        for (const std::vector<cv::Point>& contour : contours) {
            const cv::Point candidate = contour[contour.size() / 2];
            bool overlaps = false;
            for (const cv::Point& existing : labelPositions) {
                const int dx = candidate.x - existing.x;
                const int dy = candidate.y - existing.y;
                if (dx * dx + dy * dy < minimumSeparation * minimumSeparation) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                const float depthMeters = renderMetricDepth.at<float>(candidate.y, candidate.x);
                if (!std::isfinite(depthMeters) || depthMeters <= 0.0f) continue;
                const bool repeatsMetricDepth = std::any_of(
                        metricLabelDepths.begin(),
                        metricLabelDepths.end(),
                        [depthMeters](float existingDepth) {
                            const float minimumGap = std::max(
                                    kMinimumMetricLabelGap,
                                    std::min(existingDepth, depthMeters) * 0.04f
                            );
                            return std::abs(existingDepth - depthMeters) < minimumGap;
                        }
                );
                if (repeatsMetricDepth) continue;
                const cv::Point2f displayPoint = camera_geometry::distortUndistortedPoint(
                        cv::Point2f(candidate), renderDepth.cols, renderDepth.rows);
                labels.push_back(displayPoint.x);
                labels.push_back(displayPoint.y);
                labels.push_back(level);
                labels.push_back(depthMeters);
                labelPositions.push_back(candidate);
                metricLabelDepths.push_back(depthMeters);
                break;
            }
        }
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(labels.size()));
    if (result != nullptr && !labels.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(labels.size()), labels.data());
    }
    return result;
}
