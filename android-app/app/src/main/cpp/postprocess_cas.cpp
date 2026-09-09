#include "postprocess_cas.hpp"
#include "crash_reporter.hpp"
#include <volk.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstring>

#include "cas_rcas_comp_spv.hpp"

#define LOG_TAG "lsfg-cas"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGW(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_WARN,  __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO, __VA_ARGS__)

namespace lsfg_android {
namespace {

uint32_t findMemoryType(VulkanSession &vk, uint32_t bits, VkMemoryPropertyFlags wanted) {
    VkPhysicalDeviceMemoryProperties mp{};
    vk.fn.vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, &mp);
    for (uint32_t i=0;i<mp.memoryTypeCount;i++) {
        if ((bits & (1u<<i)) && (mp.memoryTypes[i].propertyFlags & wanted) == wanted) return i;
    }
    for (uint32_t i=0;i<mp.memoryTypeCount;i++)
        if (bits & (1u<<i)) return i;
    return UINT32_MAX;
}

void barrier(VulkanSession &vk, VkCommandBuffer cb, VkImage img,
             VkImageLayout oldL, VkImageLayout newL,
             VkAccessFlags srcA, VkAccessFlags dstA,
             VkPipelineStageFlags srcS, VkPipelineStageFlags dstS) {
    VkImageMemoryBarrier b{
        .sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask=srcA, .dstAccessMask=dstA,
        .oldLayout=oldL, .newLayout=newL,
        .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
        .image=img,
        .subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}
    };
    vk.fn.vkCmdPipelineBarrier(cb,srcS,dstS,0,0,nullptr,0,nullptr,1,&b);
}

} // namespace

bool CasRcasPostProcess::init(VulkanSession &vk) {
    if (pipeline_ != VK_NULL_HANDLE) return true;

    VkShaderModuleCreateInfo smci{
        .sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize=cas_rcas_comp_spv_len,
        .pCode=cas_rcas_comp_spv
    };
    if (vk.fn.vkCreateShaderModule(vk.device,&smci,nullptr,&shader_) != VK_SUCCESS) {
        LOGW("CAS/RCAS shader module creation failed");
        return false;
    }

    std::array<VkDescriptorSetLayoutBinding,2> bindings{{
        {0,VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr},
        {1,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr},
    }};
    VkDescriptorSetLayoutCreateInfo slci{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount=(uint32_t)bindings.size(),.pBindings=bindings.data()};
    if (vk.fn.vkCreateDescriptorSetLayout(vk.device,&slci,nullptr,&setLayout_) != VK_SUCCESS) return false;

    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT,0,32};
    VkPipelineLayoutCreateInfo plci{.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount=1,.pSetLayouts=&setLayout_,.pushConstantRangeCount=1,.pPushConstantRanges=&pcr};
    if (vk.fn.vkCreatePipelineLayout(vk.device,&plci,nullptr,&pipelineLayout_) != VK_SUCCESS) return false;

    VkComputePipelineCreateInfo cpci{.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .stage=VK_SHADER_STAGE_COMPUTE_BIT,.module=shader_,.pName="main"},
        .layout=pipelineLayout_};
    if (vk.fn.vkCreateComputePipelines(vk.device,VK_NULL_HANDLE,1,&cpci,nullptr,&pipeline_) != VK_SUCCESS) {
        LOGW("CAS/RCAS compute pipeline creation failed");
        return false;
    }

    VkSamplerCreateInfo sci{.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter=VK_FILTER_LINEAR,.minFilter=VK_FILTER_LINEAR,
        .mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxLod=0.0f};
    if (vk.fn.vkCreateSampler(vk.device,&sci,nullptr,&sampler_) != VK_SUCCESS) return false;

    VkDescriptorPoolSize ps[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,1}
    };
    VkDescriptorPoolCreateInfo dpci{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets=1,.poolSizeCount=2,.pPoolSizes=ps};
    if (vk.fn.vkCreateDescriptorPool(vk.device,&dpci,nullptr,&descriptorPool_) != VK_SUCCESS) return false;
    VkDescriptorSetAllocateInfo dsai{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool=descriptorPool_,.descriptorSetCount=1,.pSetLayouts=&setLayout_};
    if (vk.fn.vkAllocateDescriptorSets(vk.device,&dsai,&descriptorSet_) != VK_SUCCESS) return false;
    return true;
}

bool CasRcasPostProcess::ensureOutput(VulkanSession &vk, uint32_t w, uint32_t h) {
    if (output_ != VK_NULL_HANDLE && outputExtent_.width == w && outputExtent_.height == h) return true;
    if (output_ != VK_NULL_HANDLE) {
        vk.fn.vkDeviceWaitIdle(vk.device);
        for (auto &[image, view] : sourceViews_) {
        (void)image;
        if (view) vk.fn.vkDestroyImageView(vk.device,view,nullptr);
    }
    sourceViews_.clear();
    if (outputView_) vk.fn.vkDestroyImageView(vk.device,outputView_,nullptr);
        if (output_) vk.fn.vkDestroyImage(vk.device,output_,nullptr);
        if (outputMemory_) vk.fn.vkFreeMemory(vk.device,outputMemory_,nullptr);
        output_=VK_NULL_HANDLE; outputView_=VK_NULL_HANDLE; outputMemory_=VK_NULL_HANDLE;
        outputInitialized_ = false;
    }
    VkImageCreateInfo ici{.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType=VK_IMAGE_TYPE_2D,.format=VK_FORMAT_R8G8B8A8_UNORM,
        .extent={w,h,1},.mipLevels=1,.arrayLayers=1,.samples=VK_SAMPLE_COUNT_1_BIT,
        .tiling=VK_IMAGE_TILING_OPTIMAL,
        .usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode=VK_SHARING_MODE_EXCLUSIVE,.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED};
    if (vk.fn.vkCreateImage(vk.device,&ici,nullptr,&output_) != VK_SUCCESS) return false;
    VkMemoryRequirements mr{};
    vk.fn.vkGetImageMemoryRequirements(vk.device,output_,&mr);
    uint32_t type=findMemoryType(vk,mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type==UINT32_MAX) return false;
    VkMemoryAllocateInfo mai{.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize=mr.size,.memoryTypeIndex=type};
    if (vk.fn.vkAllocateMemory(vk.device,&mai,nullptr,&outputMemory_) != VK_SUCCESS) return false;
    if (vk.fn.vkBindImageMemory(vk.device,output_,outputMemory_,0) != VK_SUCCESS) return false;
    VkImageViewCreateInfo vci{.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=output_,
        .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};
    if (vk.fn.vkCreateImageView(vk.device,&vci,nullptr,&outputView_) != VK_SUCCESS) return false;
    outputExtent_={w,h};

    // The descriptor set is rewritten per frame because the AHB source view is
    // a transient image object owned by the render loop.
    return true;
}

bool CasRcasPostProcess::record(VulkanSession &vk, VkCommandBuffer cb,
                                VkImage src, VkImage dstSwap,
                                VkExtent2D inputExtent, VkExtent2D outputExtent,
                                PostProcessMode mode, float sharpness) {
    if (!ready() || !ensureOutput(vk,outputExtent.width,outputExtent.height)) return false;

    VkImageView srcView = VK_NULL_HANDLE;
    auto it = sourceViews_.find(src);
    if (it != sourceViews_.end()) {
        srcView = it->second;
    } else {
        VkImageViewCreateInfo vci{.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=src,
            .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};
        if (vk.fn.vkCreateImageView(vk.device,&vci,nullptr,&srcView) != VK_SUCCESS) return false;
        sourceViews_.emplace(src, srcView);
    }

    VkDescriptorImageInfo inInfo{sampler_,srcView,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo outInfo{VK_NULL_HANDLE,outputView_,VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[2]{};
    writes[0]={.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=descriptorSet_,
        .dstBinding=0,.descriptorCount=1,.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .pImageInfo=&inInfo};
    writes[1]={.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=descriptorSet_,
        .dstBinding=1,.descriptorCount=1,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .pImageInfo=&outInfo};
    vk.fn.vkUpdateDescriptorSets(vk.device,2,writes,0,nullptr);

    barrier(vk,cb,src,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_READ_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    barrier(vk,cb,output_,
            outputInitialized_ ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            outputInitialized_ ? VK_ACCESS_TRANSFER_READ_BIT : 0,
            VK_ACCESS_SHADER_WRITE_BIT,
            outputInitialized_ ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    vk.fn.vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline_);
    vk.fn.vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout_,0,1,&descriptorSet_,0,nullptr);
    struct Push { float sharpness, mode, inW, inH, outW, outH, pad0, pad1; } push{
        std::clamp(sharpness,0.0f,1.0f), (float)((int)mode),
        (float)inputExtent.width,(float)inputExtent.height,
        (float)outputExtent.width,(float)outputExtent.height,0,0};
    vk.fn.vkCmdPushConstants(cb,pipelineLayout_,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);
    vk.fn.vkCmdDispatch(cb,(outputExtent.width+7)/8,(outputExtent.height+7)/8,1);

    barrier(vk,cb,output_,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit region{
        .srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},
        .srcOffsets={{0,0,0},{static_cast<int32_t>(outputExtent.width),static_cast<int32_t>(outputExtent.height),1}},
        .dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},
        .dstOffsets={{0,0,0},{static_cast<int32_t>(outputExtent.width),static_cast<int32_t>(outputExtent.height),1}}};
    // Blit permits the common RGBA/BGRA swapchain format conversion.
    vk.fn.vkCmdBlitImage(cb,output_,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         dstSwap,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&region,VK_FILTER_NEAREST);

    barrier(vk,cb,src,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_SHADER_READ_BIT,0,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    outputInitialized_ = true;
    return true;
}

void CasRcasPostProcess::shutdown(VulkanSession &vk) {
    if (vk.device==VK_NULL_HANDLE) return;
    vk.fn.vkDeviceWaitIdle(vk.device);
    for (auto &[image, view] : sourceViews_) {
        (void)image;
        if (view) vk.fn.vkDestroyImageView(vk.device,view,nullptr);
    }
    sourceViews_.clear();
    if (outputView_) vk.fn.vkDestroyImageView(vk.device,outputView_,nullptr);
    if (output_) vk.fn.vkDestroyImage(vk.device,output_,nullptr);
    if (outputMemory_) vk.fn.vkFreeMemory(vk.device,outputMemory_,nullptr);
    if (sampler_) vk.fn.vkDestroySampler(vk.device,sampler_,nullptr);
    if (descriptorPool_) vk.fn.vkDestroyDescriptorPool(vk.device,descriptorPool_,nullptr);
    if (pipeline_) vk.fn.vkDestroyPipeline(vk.device,pipeline_,nullptr);
    if (pipelineLayout_) vk.fn.vkDestroyPipelineLayout(vk.device,pipelineLayout_,nullptr);
    if (setLayout_) vk.fn.vkDestroyDescriptorSetLayout(vk.device,setLayout_,nullptr);
    if (shader_) vk.fn.vkDestroyShaderModule(vk.device,shader_,nullptr);
    output_=VK_NULL_HANDLE; outputView_=VK_NULL_HANDLE; outputMemory_=VK_NULL_HANDLE;
    sampler_=VK_NULL_HANDLE; descriptorPool_=VK_NULL_HANDLE; descriptorSet_=VK_NULL_HANDLE;
    pipeline_=VK_NULL_HANDLE; pipelineLayout_=VK_NULL_HANDLE; setLayout_=VK_NULL_HANDLE; shader_=VK_NULL_HANDLE;
    outputExtent_={};
    outputInitialized_=false;
}

} // namespace lsfg_android
