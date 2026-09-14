#pragma once

#include "android_vk_session.hpp"

#include <volk.h>
#include <cstdint>
#include <vector>
#include <utility>

namespace lsfg_android {

/**
 * Real Vulkan compute post-process for RGBA8 AHB output images.
 *
 * The shader operates directly on the Vulkan image (no AHB CPU lock/readback).
 * It is recorded into the same command buffer as the final presentation, so
 * the framegen completion semaphore is waited before the compute dispatch and
 * the processed image is then copied to the swapchain.
 *
 * The GLSL compiler is used only once to create SPIR-V and the result lives
 * only in the Vulkan shader module/pipeline; no per-frame shader compilation.
 */
class GpuImageEnhancement {
public:
    bool initialize(VulkanSession &vk);
    bool record(VulkanSession &vk, VkCommandBuffer cb, VkImage image,
                VkExtent2D extent, float strength, float contrast, float saturation);
    void destroy(VulkanSession &vk);
    bool initialized() const { return pipeline_ != VK_NULL_HANDLE; }

private:
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    // One persistent view per output image. The render loop serializes
    // presentation, so these views remain valid until destroy().
    struct ImageBinding {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDescriptorSet set = VK_NULL_HANDLE;
    };
    std::vector<ImageBinding> bindings_;
};

} // namespace lsfg_android
