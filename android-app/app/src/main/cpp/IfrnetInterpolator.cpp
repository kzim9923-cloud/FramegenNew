// Only compiled when CMakeLists.txt found the ncnn Android prebuilt SDK
// (LSFG_HAVE_NCNN) — see that file. Built into the same lsfg-ncnn-interpolator
// static lib as NcnnInterpolator.cpp/RifeWarp.cpp, for the same -fno-rtti/
// -fno-exceptions isolation reasons documented in CMakeLists.txt.

#include "IfrnetInterpolator.hpp"
#include "IfrnetWarp.hpp"
#include "crash_reporter.hpp"
#include "ncnn_cpu_policy.hpp"

#include <net.h>
#include <mat.h>
#include <layer.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
#include <thread>

#define LOG_TAG "lsfg-ifrnet"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)

namespace lsfg_android {

namespace {

bool file_exists(const std::string &path) {
    if (FILE *f = fopen(path.c_str(), "rb")) {
        fclose(f);
        return true;
    }
    return false;
}

int round_up_to_multiple_of_32(int v) {
    return ((v + 31) / 32) * 32;
}

// CPU-only AI backend. The ncnn network uses CPU kernels and the full
// online CPU topology; no CPU affinity or GPU compute is requested.
::ncnn::Layer *ifrnet_warp_layer_creator(void * /*userdata*/) {
    return new IfrnetWarp();
}

} // namespace

struct IfrnetInterpolator::Impl {
    // CPU network used for the CPU frame-generation path.
    ncnn::Net ifrnetCpu;

    // Tracks whether ifrnetCpu currently holds a successfully loaded
    // model. Set true at the end of a successful load(), cleared by
    // unload() and checked by isLoaded()/interpolate().
    bool cpuLoaded = false;


    // Single IFRNet forward pass on a specific network: predicts the frame
    // at `timestep` (in [0,1], 0 = exactly frame a, 1 = exactly frame c)
    // between two already-padded, normalized-to-[0,1], full-res RGB Mats.
    // Same in0/in1/in2 -> out0 blob contract as NcnnInterpolator::Impl.
    // Takes the net explicitly so the hybrid CPU and GPU halves can call
    // this from two different threads at once without sharing an
    // Extractor.
    static int predict(ncnn::Net &net, const ncnn::Mat &aPadded, const ncnn::Mat &cPadded,
                        int wPadded, int hPadded, float timestep, ncnn::Mat &outPadded) {
        ncnn::Mat timestepMap;
        timestepMap.create(wPadded, hPadded, 1);
        if (timestepMap.empty()) return kNcnnErrLoadFailed;
        timestepMap.fill(timestep);

        ncnn::Extractor ex = net.create_extractor();
        if (ex.input("in0", aPadded) != 0) return kNcnnErrLoadFailed;
        if (ex.input("in1", cPadded) != 0) return kNcnnErrLoadFailed;
        if (ex.input("in2", timestepMap) != 0) return kNcnnErrLoadFailed;
        if (ex.extract("out0", outPadded) != 0) return kNcnnErrLoadFailed;
        return kNcnnOk;
    }

    // Runs predict() for output index k (1-based, matching interpolate()'s
    // loop) and crops/denormalizes straight into outFrames[k - 1]. Shared
    // by the single-path and hybrid loops below so the pad/crop math only
    // lives in one place.
    static int predictOne(ncnn::Net &net, const ncnn::Mat &aPadded, const ncnn::Mat &cPadded,
                           int wPadded, int hPadded, int width, int height,
                           int k, int multiplier, uint8_t **outFrames) {
        const float timestep = static_cast<float>(k) / static_cast<float>(multiplier);
        ncnn::Mat outPadded;
        int rc = predict(net, aPadded, cPadded, wPadded, hPadded, timestep, outPadded);
        if (rc != kNcnnOk) return rc;

        ncnn::Mat outCropped(width, height, 3);
        if (outCropped.empty()) return kNcnnErrLoadFailed;
        for (int q = 0; q < 3; q++) {
            float *outptr = outCropped.channel(q);
            for (int y = 0; y < height; y++) {
                const float *inptr = outPadded.channel(q).row(y);
                for (int x = 0; x < width; x++) {
                    *outptr++ = std::min(std::max(inptr[x] * 255.f + 0.5f, 0.f), 255.f);
                }
            }
        }
        outCropped.to_pixels(outFrames[k - 1], ncnn::Mat::PIXEL_RGB2RGBA);
        return kNcnnOk;
    }
};

IfrnetInterpolator::IfrnetInterpolator() : impl_(new Impl()) {}

IfrnetInterpolator::~IfrnetInterpolator() {
    unload();
    delete impl_;
}

int IfrnetInterpolator::load(const std::string &modelDir, bool allowGpu, int vulkanDeviceIndex, int numThreads) {
    // See gpuLoadMutex()'s comment in NcnnInterpolator.hpp: this serializes
    // load()/unload() against NcnnInterpolator's AND IfrnetInterpolator's
    // instances process-wide, since the settings-screen test instance and
    // the live session's instance both bind to the same Vulkan device.
    std::lock_guard<std::recursive_mutex> gpuLock(gpuLoadMutex());
    unload();

    const std::string ifrnetParam = modelDir + "/ifrnet.param";
    const std::string ifrnetBin   = modelDir + "/ifrnet.bin";

    for (const auto &p : {ifrnetParam, ifrnetBin}) {
        if (!file_exists(p)) {
            LOGE("model file missing: %s (expected ifrnet.param/ifrnet.bin in modelDir)", p.c_str());
            return kNcnnErrModelMissing;
        }
    }

    (void)allowGpu; (void)vulkanDeviceIndex; (void)numThreads; // CPU-only policy intentionally ignores GPU selection and thread caps.
    ncnnCpuSetAllCores();
    const int kUtilityThreads = ncnnCpuThreadCount();

    ncnn::Option baseOpt;
    baseOpt.num_threads = kUtilityThreads;
    baseOpt.use_fp16_packed = true;
    baseOpt.use_fp16_storage = true;
    baseOpt.use_fp16_arithmetic = true;
    baseOpt.use_winograd_convolution = false;
    baseOpt.use_sgemm_convolution = true;

    // Keep Vulkan compute disabled: this AI backend is intentionally CPU-only,
    // leaving the GPU available for the dedicated LSFG path.
    baseOpt.use_vulkan_compute = false;
    impl_->ifrnetCpu.opt = baseOpt;
    impl_->ifrnetCpu.register_custom_layer("ifrnet.Warp", ifrnet_warp_layer_creator);

    if (impl_->ifrnetCpu.load_param(ifrnetParam.c_str()) != 0 ||
        impl_->ifrnetCpu.load_model(ifrnetBin.c_str()) != 0) {
        LOGE("CPU-only IFRNet model load failed from %s", modelDir.c_str());
        impl_->ifrnetCpu.clear();
        return kNcnnErrLoadFailed;
    }
    impl_->cpuLoaded = true;

    LOGI("ncnn IFRNet model loaded from %s (mode=CPU-only, threads=%d, Vulkan=off)",
         modelDir.c_str(), ncnnCpuThreadCount());
    return kNcnnOk;
}

void IfrnetInterpolator::unload() {
    std::lock_guard<std::recursive_mutex> gpuLock(gpuLoadMutex());
    if (impl_->cpuLoaded) {
        impl_->ifrnetCpu.clear();
        impl_->cpuLoaded = false;
    }
}

bool IfrnetInterpolator::isLoaded() const {
    return impl_->cpuLoaded;
}


int IfrnetInterpolator::interpolate(const uint8_t *frameA, const uint8_t *frameC,
                                     int width, int height,
                                     uint8_t **outFrames, int multiplier,
                                     float /*flowScale — unused, see header*/) {
    if (!impl_->cpuLoaded) {
        return kNcnnErrNotLoaded;
    }
    if (width <= 0 || height <= 0 || multiplier < 2 || multiplier > 8 ||
        frameA == nullptr || frameC == nullptr || outFrames == nullptr) {
        return kNcnnErrBadArgs;
    }

    ncnn::Mat fullA = ncnn::Mat::from_pixels(frameA, ncnn::Mat::PIXEL_RGBA2RGB, width, height);
    ncnn::Mat fullC = ncnn::Mat::from_pixels(frameC, ncnn::Mat::PIXEL_RGBA2RGB, width, height);

    const int wPadded = round_up_to_multiple_of_32(width);
    const int hPadded = round_up_to_multiple_of_32(height);

    // Normalize to [0,1] and zero-pad up to (wPadded, hPadded), matching
    // ifrnet-ncnn-vulkan's own preproc exactly (constant zero padding, not
    // edge replication) — same rule as NcnnInterpolator, confirmed against
    // upstream src/ifrnet.cpp's "pad to 32n" comment.
    ncnn::Mat aPadded(wPadded, hPadded, 3);
    ncnn::Mat cPadded(wPadded, hPadded, 3);
    if (aPadded.empty() || cPadded.empty()) {
        return kNcnnErrLoadFailed;
    }
    auto normalizeAndPad = [&](const ncnn::Mat &src, ncnn::Mat &dst) {
        for (int q = 0; q < 3; q++) {
            float *outptr = dst.channel(q);
            for (int y = 0; y < hPadded; y++) {
                const float *inptr = (y < height) ? src.channel(q).row(y) : nullptr;
                for (int x = 0; x < wPadded; x++) {
                    *outptr++ = (inptr != nullptr && x < width) ? inptr[x] * (1.f / 255.f) : 0.f;
                }
            }
        }
    };
    normalizeAndPad(fullA, aPadded);
    normalizeAndPad(fullC, cPadded);

    // CPU-only inference. The network was loaded with Vulkan compute disabled.
    if (!impl_->cpuLoaded) {
        return kNcnnErrNotLoaded;
    }
    for (int k = 1; k < multiplier; k++) {
        int rc = Impl::predictOne(impl_->ifrnetCpu, aPadded, cPadded,
                                   wPadded, hPadded, width, height,
                                   k, multiplier, outFrames);
        if (rc != kNcnnOk) {
            LOGE("CPU-only IFRNet inference failed rc=%d", rc);
            return rc;
        }
    }
    return kNcnnOk;

}

} // namespace lsfg_android
