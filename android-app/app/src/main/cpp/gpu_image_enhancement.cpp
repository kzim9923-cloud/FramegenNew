#include "gpu_image_enhancement.hpp"

#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <android/log.h>
#include <array>
#include <algorithm>
#include <string>
#include <vector>

#define LOG_TAG "lsfg-gpu-enhance"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace lsfg_android {
namespace {

const char *kComputeShader = R"GLSL(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0, rgba8) readonly uniform image2D srcImage;
layout(binding = 1, rgba8) writeonly uniform image2D dstImage;

layout(push_constant) uniform Params {
    float contrast;
    float saturation;
    float srcWidth;
    float srcHeight;
    float dstWidth;
    float dstHeight;
    float filterMode;
    float aaMode; // 0 = off, 1 = FXAA, 2 = SMAA (lightweight single-pass)
} p;

vec4 enhance(vec4 c) {
    c.rgb = (c.rgb - 0.5) * p.contrast + 0.5;
    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c.rgb = luma + (c.rgb - luma) * p.saturation;
    c.rgb = clamp(c.rgb, 0.0, 1.0);
    return c;
}

ivec2 clampSrc(ivec2 q) {
    return clamp(q, ivec2(0), ivec2(int(p.srcWidth) - 1, int(p.srcHeight) - 1));
}

vec4 rawLoad(ivec2 q) {
    return imageLoad(srcImage, clampSrc(q));
}

float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Single-pass FXAA-style edge smoothing: detects local contrast against the
// 3x3 neighborhood, picks the dominant edge direction via a Sobel-like
// gradient, blends along that edge, then adds a small subpixel blend toward
// the 3x3 average so thin/diagonal aliasing is softened too. Flat regions
// (no contrast) return the source sample unchanged so text/UI stays crisp.
vec4 fxaa(ivec2 q) {
    vec4 cC  = rawLoad(q);
    vec4 cN  = rawLoad(q + ivec2( 0,-1));
    vec4 cS  = rawLoad(q + ivec2( 0, 1));
    vec4 cE  = rawLoad(q + ivec2( 1, 0));
    vec4 cW  = rawLoad(q + ivec2(-1, 0));
    vec4 cNW = rawLoad(q + ivec2(-1,-1));
    vec4 cNE = rawLoad(q + ivec2( 1,-1));
    vec4 cSW = rawLoad(q + ivec2(-1, 1));
    vec4 cSE = rawLoad(q + ivec2( 1, 1));

    float lC = lumaOf(cC.rgb), lN = lumaOf(cN.rgb), lS = lumaOf(cS.rgb);
    float lE = lumaOf(cE.rgb), lW = lumaOf(cW.rgb);
    float lNW = lumaOf(cNW.rgb), lNE = lumaOf(cNE.rgb);
    float lSW = lumaOf(cSW.rgb), lSE = lumaOf(cSE.rgb);

    float lMin = min(lC, min(min(lN, lS), min(lE, lW)));
    float lMax = max(lC, max(max(lN, lS), max(lE, lW)));
    float range = lMax - lMin;

    float threshold = max(0.0312, lMax * 0.125);
    if (range < threshold) return cC;

    float edgeHoriz = abs((lNW + lSW) - 2.0 * lW) + 2.0 * abs((lN + lS) - 2.0 * lC) + abs((lNE + lSE) - 2.0 * lE);
    float edgeVert  = abs((lNW + lNE) - 2.0 * lN) + 2.0 * abs((lW + lE) - 2.0 * lC) + abs((lSW + lSE) - 2.0 * lS);
    bool horizontal = edgeHoriz >= edgeVert;

    vec4 c1 = horizontal ? cN : cW;
    vec4 c2 = horizontal ? cS : cE;
    float l1 = horizontal ? lN : lW;
    float l2 = horizontal ? lS : lE;

    float gradient = abs(l1 - l2);
    float blend = clamp(gradient / max(range, 1e-4), 0.0, 1.0) * 0.5;
    vec4 blended = mix(cC, mix(c1, c2, 0.5), blend);

    vec4 avg3x3 = (cC + cN + cS + cE + cW + cNW + cNE + cSW + cSE) / 9.0;
    float subpixBlend = clamp(range * 2.0, 0.0, 1.0) * 0.25;
    return mix(blended, avg3x3, subpixBlend);
}

// Lightweight single-pass approximation of SMAA. True SMAA detects edges,
// then walks each edge to measure its length and looks up analytic coverage
// from a precomputed area/search texture across three separate passes. That
// isn't practical inside one compute pass with no extra bound textures, so
// this instead walks a short (4-tap) falloff-weighted line in the direction
// perpendicular to the detected edge and blends toward it proportionally to
// local contrast — approximating SMAA's "coverage grows with edge strength"
// behaviour with a wider, smoother kernel than fxaa() above.
vec4 smaaLite(ivec2 q) {
    vec4 cC = rawLoad(q);
    float lC = lumaOf(cC.rgb);
    float lN = lumaOf(rawLoad(q + ivec2(0, -1)).rgb);
    float lS = lumaOf(rawLoad(q + ivec2(0,  1)).rgb);
    float lE = lumaOf(rawLoad(q + ivec2(1,  0)).rgb);
    float lW = lumaOf(rawLoad(q + ivec2(-1, 0)).rgb);

    float deltaV = max(abs(lC - lN), abs(lC - lS));
    float deltaH = max(abs(lC - lE), abs(lC - lW));
    const float threshold = 0.05;
    if (deltaV < threshold && deltaH < threshold) return cC;

    vec4 vAccum = cC; float vWeight = 1.0;
    vec4 hAccum = cC; float hWeight = 1.0;
    for (int i = 1; i <= 4; i++) {
        float falloff = 1.0 / float(i + 1);
        vAccum += (rawLoad(q + ivec2(0, -i)) + rawLoad(q + ivec2(0, i))) * falloff;
        vWeight += 2.0 * falloff;
        hAccum += (rawLoad(q + ivec2(-i, 0)) + rawLoad(q + ivec2(i, 0))) * falloff;
        hWeight += 2.0 * falloff;
    }
    vec4 vBlur = vAccum / vWeight;
    vec4 hBlur = hAccum / hWeight;

    float wV = deltaV / max(deltaV + deltaH, 1e-4);
    vec4 directional = mix(hBlur, vBlur, wV);

    float coverage = clamp(max(deltaV, deltaH) * 3.0, 0.0, 0.7);
    return mix(cC, directional, coverage);
}

vec4 antiAlias(ivec2 q) {
    if (p.aaMode > 1.5) return smaaLite(q);
    if (p.aaMode > 0.5) return fxaa(q);
    return rawLoad(q);
}

vec4 sampleSource(ivec2 q) {
    return enhance(antiAlias(q));
}

void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dstSize = imageSize(dstImage);
    if (q.x >= dstSize.x || q.y >= dstSize.y) return;

    // Map destination pixel centers into source pixel space. Enhancement is
    // evaluated before interpolation, so the logical order is:
    // source -> enhancement -> selected upscale filter -> destination.
    vec2 srcPos = ((vec2(q) + vec2(0.5)) *
                   vec2(p.srcWidth, p.srcHeight) /
                   vec2(p.dstWidth, p.dstHeight)) - vec2(0.5);

    vec4 result;
    if (p.filterMode < 0.5) {
        result = sampleSource(ivec2(floor(srcPos + vec2(0.5))));
    } else {
        ivec2 base = ivec2(floor(srcPos));
        vec2 f = fract(srcPos);
        vec4 c00 = sampleSource(base);
        vec4 c10 = sampleSource(base + ivec2(1, 0));
        vec4 c01 = sampleSource(base + ivec2(0, 1));
        vec4 c11 = sampleSource(base + ivec2(1, 1));
        result = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    }

    imageStore(dstImage, q, result);
}
)GLSL";

bool compileShader(std::vector<uint32_t> &spirv) {
    if (!glslang::InitializeProcess()) {
        LOGE("glslang InitializeProcess failed");
        return false;
    }

    glslang::TShader shader(EShLangCompute);
    shader.setStrings(&kComputeShader, 1);
    shader.setEntryPoint("main");
    shader.setSourceEntryPoint("main");
    shader.setEnvInput(glslang::EShSourceGlsl, EShLangCompute,
                       glslang::EShClientVulkan, 0);
    shader.setEnvClient(glslang::EShClientVulkan,
                        glslang::EShTargetVulkan_1_1);
    shader.setEnvTarget(glslang::EShTargetSpv,
                        glslang::EShTargetSpv_1_3);

    const TBuiltInResource *resources = GetDefaultResources();
    const EShMessages messages =
        static_cast<EShMessages>(EShMsgVulkanRules | EShMsgSpvRules);

    const bool parsed = shader.parse(resources, 450, false, messages);
    if (!parsed) {
        LOGE("GPU enhancement shader parse failed: %s", shader.getInfoLog());
        LOGE("GPU enhancement shader debug: %s", shader.getInfoDebugLog());
        glslang::FinalizeProcess();
        return false;
    }

    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages)) {
        LOGE("GPU enhancement shader link failed: %s", program.getInfoLog());
        LOGE("GPU enhancement shader debug: %s", program.getInfoDebugLog());
        glslang::FinalizeProcess();
        return false;
    }

    glslang::SpvOptions options;
    options.generateDebugInfo = false;
    options.disableOptimizer = false;
    options.optimizeSize = true;
    glslang::GlslangToSpv(
        *program.getIntermediate(EShLangCompute), spirv, &options);

    glslang::FinalizeProcess();
    return !spirv.empty();
}

} // namespace

bool GpuImageEnhancement::initialize(VulkanSession &vk) {
    if (pipeline_ != VK_NULL_HANDLE) return true;
    if (vk.device == VK_NULL_HANDLE) return false;

    std::vector<uint32_t> spirv;
    if (!compileShader(spirv)) return false;

    const VkShaderModuleCreateInfo smi{
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = spirv.size() * sizeof(uint32_t),
        .pCode = spirv.data(),
    };
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vk.fn.vkCreateShaderModule(vk.device, &smi, nullptr, &shaderModule) != VK_SUCCESS) {
        LOGE("vkCreateShaderModule failed");
        return false;
    }

    const std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    }};
    const VkDescriptorSetLayoutCreateInfo dli{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = static_cast<uint32_t>(bindings.size()),
        .pBindings = bindings.data(),
    };
    if (vk.fn.vkCreateDescriptorSetLayout(vk.device, &dli, nullptr,
                                           &descriptorSetLayout_) != VK_SUCCESS) {
        vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
        return false;
    }

    const VkPushConstantRange push{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = sizeof(float) * 8,
    };
    const VkPipelineLayoutCreateInfo pli{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &descriptorSetLayout_,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push,
    };
    if (vk.fn.vkCreatePipelineLayout(vk.device, &pli, nullptr,
                                     &pipelineLayout_) != VK_SUCCESS) {
        vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
        return false;
    }

    const VkPipelineShaderStageCreateInfo stage{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .stage = VK_SHADER_STAGE_COMPUTE_BIT,
        .module = shaderModule,
        .pName = "main",
    };
    const VkComputePipelineCreateInfo pci{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = stage,
        .layout = pipelineLayout_,
    };
    const VkResult pr = vk.fn.vkCreateComputePipelines(
        vk.device, VK_NULL_HANDLE, 1, &pci, nullptr, &pipeline_);
    vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
    if (pr != VK_SUCCESS) {
        LOGE("vkCreateComputePipelines failed (%d)", static_cast<int>(pr));
        destroy(vk);
        return false;
    }

    const VkDescriptorPoolSize poolSize{
        .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .descriptorCount = 32,
    };
    const VkDescriptorPoolCreateInfo dpi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 16,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
    };
    if (vk.fn.vkCreateDescriptorPool(vk.device, &dpi, nullptr,
                                      &descriptorPool_) != VK_SUCCESS) {
        destroy(vk);
        return false;
    }

    LOGI("Real Vulkan image-enhancement + upscale compute pipeline initialized");
    return true;
}

bool GpuImageEnhancement::record(VulkanSession &vk, VkCommandBuffer cb,
                                 VkImage source, VkExtent2D sourceExtent,
                                 VkImage destination, VkExtent2D destinationExtent,
                                 int upscaleFilterMode, float contrast,
                                 float saturation, int antiAliasMode) {
    if (!initialize(vk) || cb == VK_NULL_HANDLE ||
        source == VK_NULL_HANDLE || destination == VK_NULL_HANDLE ||
        sourceExtent.width == 0 || sourceExtent.height == 0 ||
        destinationExtent.width == 0 || destinationExtent.height == 0) {
        return false;
    }

    VkImageView sourceView = VK_NULL_HANDLE;
    for (const auto &entry : sourceViews_) {
        if (entry.image == source) {
            sourceView = entry.view;
            break;
        }
    }
    if (sourceView == VK_NULL_HANDLE) {
        VkImageViewCreateInfo vci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = source,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1
            },
        };
        if (vk.fn.vkCreateImageView(vk.device, &vci, nullptr, &sourceView) != VK_SUCCESS) {
            LOGW("GPU enhancement: source vkCreateImageView failed");
            return false;
        }
        sourceViews_.push_back({source, sourceView});
    }

    DestinationBinding *destinationBinding = nullptr;
    for (auto &entry : destinationBindings_) {
        if (entry.image == destination) {
            destinationBinding = &entry;
            break;
        }
    }
    if (destinationBinding == nullptr) {
        if (destinationBindings_.size() >= 16) {
            LOGW("GPU enhancement: too many destination images");
            return false;
        }

        VkImageViewCreateInfo vci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = destination,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1
            },
        };
        VkImageView view = VK_NULL_HANDLE;
        if (vk.fn.vkCreateImageView(vk.device, &vci, nullptr, &view) != VK_SUCCESS) {
            LOGW("GPU enhancement: destination vkCreateImageView failed");
            return false;
        }

        const VkDescriptorSetAllocateInfo dai{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = descriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &descriptorSetLayout_,
        };
        VkDescriptorSet set = VK_NULL_HANDLE;
        if (vk.fn.vkAllocateDescriptorSets(vk.device, &dai, &set) != VK_SUCCESS) {
            vk.fn.vkDestroyImageView(vk.device, view, nullptr);
            LOGW("GPU enhancement: vkAllocateDescriptorSets failed");
            return false;
        }

        destinationBindings_.push_back({destination, view, set});
        destinationBinding = &destinationBindings_.back();
    }

    const VkDescriptorImageInfo srcInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = sourceView,
        .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
    };
    const VkDescriptorImageInfo dstInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = destinationBinding->view,
        .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
    };
    const std::array<VkWriteDescriptorSet, 2> writes{{
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = destinationBinding->set,
            .dstBinding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &srcInfo,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = destinationBinding->set,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &dstInfo,
        },
    }};
    vk.fn.vkUpdateDescriptorSets(vk.device, static_cast<uint32_t>(writes.size()),
                                 writes.data(), 0, nullptr);

    vk.fn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vk.fn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  pipelineLayout_, 0, 1, &destinationBinding->set,
                                  0, nullptr);

    const std::array<float, 8> params{
        std::clamp(contrast, 0.5f, 1.5f),
        std::clamp(saturation, 0.0f, 2.0f),
        static_cast<float>(sourceExtent.width),
        static_cast<float>(sourceExtent.height),
        static_cast<float>(destinationExtent.width),
        static_cast<float>(destinationExtent.height),
        static_cast<float>(std::clamp(upscaleFilterMode, 0, 1)),
        static_cast<float>(std::clamp(antiAliasMode, 0, 2)),
    };
    vk.fn.vkCmdPushConstants(cb, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                             0, sizeof(params), params.data());

    vk.fn.vkCmdDispatch(cb, (destinationExtent.width + 7) / 8,
                        (destinationExtent.height + 7) / 8, 1);
    return true;
}

void GpuImageEnhancement::clearDestinationBindings(VulkanSession &vk) {
    if (vk.device == VK_NULL_HANDLE) return;
    for (const auto &entry : destinationBindings_) {
        if (entry.view != VK_NULL_HANDLE && vk.fn.vkDestroyImageView)
            vk.fn.vkDestroyImageView(vk.device, entry.view, nullptr);
    }
    destinationBindings_.clear();
}

void GpuImageEnhancement::destroy(VulkanSession &vk) {
    if (vk.device == VK_NULL_HANDLE) return;

    clearDestinationBindings(vk);

    for (const auto &entry : sourceViews_) {
        if (entry.view != VK_NULL_HANDLE && vk.fn.vkDestroyImageView)
            vk.fn.vkDestroyImageView(vk.device, entry.view, nullptr);
    }
    sourceViews_.clear();

    if (descriptorPool_ != VK_NULL_HANDLE && vk.fn.vkDestroyDescriptorPool)
        vk.fn.vkDestroyDescriptorPool(vk.device, descriptorPool_, nullptr);
    descriptorPool_ = VK_NULL_HANDLE;

    if (pipeline_ != VK_NULL_HANDLE && vk.fn.vkDestroyPipeline)
        vk.fn.vkDestroyPipeline(vk.device, pipeline_, nullptr);
    pipeline_ = VK_NULL_HANDLE;

    if (pipelineLayout_ != VK_NULL_HANDLE && vk.fn.vkDestroyPipelineLayout)
        vk.fn.vkDestroyPipelineLayout(vk.device, pipelineLayout_, nullptr);
    pipelineLayout_ = VK_NULL_HANDLE;

    if (descriptorSetLayout_ != VK_NULL_HANDLE && vk.fn.vkDestroyDescriptorSetLayout)
        vk.fn.vkDestroyDescriptorSetLayout(vk.device, descriptorSetLayout_, nullptr);
    descriptorSetLayout_ = VK_NULL_HANDLE;
}

} // namespace lsfg_android
