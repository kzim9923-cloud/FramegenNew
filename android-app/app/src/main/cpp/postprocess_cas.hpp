#pragma once
#include "android_vk_session.hpp"
#include <cstdint>
#include <unordered_map>

namespace lsfg_android {

enum class PostProcessMode : int32_t { OFF = 0, CAS = 1, RCAS = 2 };

class CasRcasPostProcess {
public:
    bool init(VulkanSession &vk);
    void shutdown(VulkanSession &vk);
    bool ensureOutput(VulkanSession &vk, uint32_t w, uint32_t h);
    bool record(VulkanSession &vk, VkCommandBuffer cb,
                VkImage src, VkImage dstSwap,
                VkExtent2D inputExtent, VkExtent2D outputExtent,
                PostProcessMode mode, float sharpness);
    bool ready() const { return pipeline_ != VK_NULL_HANDLE; }

private:
    VkShaderModule shader_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkImage output_ = VK_NULL_HANDLE;
    VkDeviceMemory outputMemory_ = VK_NULL_HANDLE;
    VkImageView outputView_ = VK_NULL_HANDLE;
    VkExtent2D outputExtent_{};
    bool outputInitialized_ = false;
    std::unordered_map<VkImage, VkImageView> sourceViews_;
};

} // namespace lsfg_android
