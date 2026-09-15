#include "gpu_image_enhancement.hpp"

#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <android/log.h>
#include <array>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>

#define LOG_TAG "lsfg-gpu-enhance"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace lsfg_android {
namespace {

const char *kComputeShader = R"GLSL(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0, rgba8) uniform image2D img;

layout(push_constant) uniform Params {
    float contrast;
    float saturation;
} p;

void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(img);
    if (q.x >= size.x || q.y >= size.y) return;

    vec4 center = imageLoad(img, q);

    // Safe in-place GPU pass: color controls are evaluated from the
    // original pixel, so there is no read/write race between workgroups.
    center.rgb = (center.rgb - 0.5) * p.contrast + 0.5;

    float luma = dot(center.rgb, vec3(0.2126, 0.7152, 0.0722));
    center.rgb = luma + (center.rgb - luma) * p.saturation;

    center.rgb = clamp(center.rgb, 0.0, 1.0);
    imageStore(img, q, center);
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
    glslang::GlslangToSpv(*program.getIntermediate(EShLangCompute), spirv, &options);

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

    const VkDescriptorSetLayoutBinding binding{
        .binding = 0,
        .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .descriptorCount = 1,
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
    };
    const VkDescriptorSetLayoutCreateInfo dli{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 1,
        .pBindings = &binding,
    };
    if (vk.fn.vkCreateDescriptorSetLayout(vk.device, &dli, nullptr,
                                           &descriptorSetLayout_) != VK_SUCCESS) {
        vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
        return false;
    }

    const VkPushConstantRange push{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = sizeof(float) * 2,
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
        .descriptorCount = 8,
    };
    const VkDescriptorPoolCreateInfo dpi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 8,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
    };
    if (vk.fn.vkCreateDescriptorPool(vk.device, &dpi, nullptr,
                                      &descriptorPool_) != VK_SUCCESS) {
        destroy(vk);
        return false;
    }

    // Descriptor sets are allocated lazily per output image in record().

    LOGI("Real Vulkan image-enhancement compute pipeline initialized");
    return true;
}

bool GpuImageEnhancement::record(VulkanSession &vk, VkCommandBuffer cb, VkImage image,
                                 VkExtent2D extent, float contrast,
                                 float saturation) {
    if (!initialize(vk) || cb == VK_NULL_HANDLE || image == VK_NULL_HANDLE ||
        extent.width == 0 || extent.height == 0) {
        return false;
    }

    VkImageView view = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    for (const auto &entry : bindings_) {
        if (entry.image == image) {
            view = entry.view;
            descriptorSet = entry.set;
            break;
        }
    }

    if (descriptorSet == VK_NULL_HANDLE) {
        if (bindings_.size() >= 8) {
            LOGW("GPU enhancement: too many distinct output images");
            return false;
        }

        VkImageViewCreateInfo vci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = image,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1
            },
        };
        if (vk.fn.vkCreateImageView(vk.device, &vci, nullptr, &view) != VK_SUCCESS) {
            LOGW("GPU enhancement: vkCreateImageView failed");
            return false;
        }

        const VkDescriptorSetAllocateInfo dai{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = descriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &descriptorSetLayout_,
        };
        if (vk.fn.vkAllocateDescriptorSets(vk.device, &dai, &descriptorSet) != VK_SUCCESS) {
            vk.fn.vkDestroyImageView(vk.device, view, nullptr);
            LOGW("GPU enhancement: vkAllocateDescriptorSets failed");
            return false;
        }

        const VkDescriptorImageInfo di{
            .sampler = VK_NULL_HANDLE,
            .imageView = view,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        const VkWriteDescriptorSet write{
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = descriptorSet,
            .dstBinding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &di,
        };
        vk.fn.vkUpdateDescriptorSets(vk.device, 1, &write, 0, nullptr);
        bindings_.push_back({image, view, descriptorSet});
    }

    vk.fn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vk.fn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    const std::array<float, 2> params{
        std::clamp(contrast, 0.5f, 1.5f),
        std::clamp(saturation, 0.0f, 2.0f),
    };
    vk.fn.vkCmdPushConstants(cb, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                             0, sizeof(params), params.data());

    vk.fn.vkCmdDispatch(cb, (extent.width + 7) / 8, (extent.height + 7) / 8, 1);

    return true;
}

void GpuImageEnhancement::destroy(VulkanSession &vk) {
    if (vk.device == VK_NULL_HANDLE) return;
    for (const auto &entry : bindings_) {
        if (entry.view != VK_NULL_HANDLE && vk.fn.vkDestroyImageView)
            vk.fn.vkDestroyImageView(vk.device, entry.view, nullptr);
    }
    bindings_.clear();
    if (descriptorPool_ != VK_NULL_HANDLE && vk.fn.vkDestroyDescriptorPool)
        vk.fn.vkDestroyDescriptorPool(vk.device, descriptorPool_, nullptr);
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;

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
