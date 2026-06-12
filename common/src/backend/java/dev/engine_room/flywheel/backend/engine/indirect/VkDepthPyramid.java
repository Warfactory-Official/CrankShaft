package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import net.minecraft.util.Mth;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * The HiZ depth pyramid for the Vulkan cull: a mip-chained R32F image, min-reduced (reversed-Z).
 */
public final class VkDepthPyramid {
    private static final int VMA_MEMORY_USAGE_AUTO = 8;
    private static final int FORMAT = VK12.VK_FORMAT_R32_SFLOAT;

    private final long vma;
    private long image;
    private long allocation;
    private long sampledView;
    private long[] mipViews = new long[0];
    private int width = -1;
    private int height = -1;
    private int mipLevels;

    public VkDepthPyramid() {
        this.vma = VkContext.vma();
    }

    public static int mip0Size(int screenSize) {
        int nextPot = screenSize <= 1 ? 1 : Integer.highestOneBit(screenSize - 1) << 1;
        return Math.max(nextPot >> 1, 1);
    }

    public static int mipLevelsFor(int w, int h) {
        int result = 1;
        while (w > 1 && h > 1) {
            result++;
            w >>= 1;
            h >>= 1;
        }
        return result;
    }

    private static void barrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess)
                                                      .dstAccessMask(dstAccess);
            VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " during VkDepthPyramid " + what);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int mipLevels() {
        return mipLevels;
    }

    public void resize(int framebufferWidth, int framebufferHeight) {
        int w = mip0Size(framebufferWidth);
        int h = mip0Size(framebufferHeight);
        if (w == width && h == height) {
            return;
        }
        destroyResources();
        width = w;
        height = h;
        mipLevels = mipLevelsFor(w, h);
        create();
    }

    public long sampledView() {
        return sampledView;
    }

    public void regenerate(VkCommandBuffer cmd, long depthView, long sampler, VkComputePipeline first,
                           VkComputePipeline second, VkDescriptorWriter writer) {
        if (width < 0) {
            return;
        }
        // srcStage carries COMPUTE (+TASK on the mesh tier) as a WAR guard: earlier submits' culls SAMPLE this pyramid, and the rebuild overwrites it in place -- without the ordering the culls read half-updated mips (over-cull).
        int warStages = VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                | (VkCaps.MESH_SHADER_NEGOTIATED ? EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT : 0);
        barrier(cmd, VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                        | warStages,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);

        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, first.handle());
        writer.image(1, mipView(0)).sampler(10, depthView, sampler);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, first.layout());
        VK12.vkCmdDispatch(cmd, Mth.positiveCeilDiv(width << 1, 64), Mth.positiveCeilDiv(height << 1, 64), 1);

        barrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);

        long secondLayout = second.layout().pipelineLayout();
        boolean firstGroup = true;
        for (int base = 0; base + 1 < mipLevels; base += 6) {
            if (!firstGroup) {
                barrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);
            }
            firstGroup = false;
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, second.handle());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer pc = stack.malloc(8);
                pc.putInt(0, mipLevels);
                pc.putInt(4, base);
                VK12.vkCmdPushConstants(cmd, secondLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }
            for (int i = 0; i <= 6; i++) {
                writer.image(i, mipView(base + i));
            }
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, second.layout());
            VK12.vkCmdDispatch(cmd, Mth.positiveCeilDiv(width >> base, 64), Mth.positiveCeilDiv(height >> base, 64), 1);
        }

        barrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    public long mipView(int level) {
        return mipViews[Math.min(level, mipLevels - 1)];
    }

    private void create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                                                      .sType$Default()
                                                      .imageType(VK12.VK_IMAGE_TYPE_2D)
                                                      .format(FORMAT)
                                                      .mipLevels(mipLevels)
                                                      .arrayLayers(1)
                                                      .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                                                      .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                                                      .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                                      .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                                                      .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            LongBuffer pImage = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);
            check(Vma.vmaCreateImage(vma, info, allocInfo, pImage, pAlloc, null), "create");
            image = pImage.get(0);
            allocation = pAlloc.get(0);

            mipViews = new long[mipLevels];
            for (int i = 0; i < mipLevels; i++) {
                mipViews[i] = createView(stack, i, 1);
            }
            sampledView = createView(stack, 0, mipLevels);
        }

        VkCommandBuffer cmd = VkContext.beginCommands();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
                                                                .sType$Default()
                                                                .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                                                                .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                                                                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                                                                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                                                                .image(image)
                                                                .srcAccessMask(0)
                                                                .dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            b.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(mipLevels).layerCount(1);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, b);

            VkClearColorValue clear = VkClearColorValue.calloc(stack); // all zero
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                                                                          .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                                                                          .levelCount(mipLevels).layerCount(1);
            VK12.vkCmdClearColorImage(cmd, image, VK12.VK_IMAGE_LAYOUT_GENERAL, clear, range);
        }
        VkContext.submitCommands(cmd);
    }

    private long createView(MemoryStack stack, int baseMip, int levelCount) {
        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                                                              .sType$Default()
                                                              .image(image)
                                                              .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                                                              .format(FORMAT);
        viewInfo.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseMip).levelCount(levelCount).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.callocLong(1);
        check(VK12.vkCreateImageView(VkContext.vkDevice(), viewInfo, null, pView), "view");
        return pView.get(0);
    }

    private void destroyResources() {
        if (image == 0L) {
            return;
        }
        long im = image;
        long al = allocation;
        long sv = sampledView;
        long[] mv = mipViews;
        long vmaHandle = vma;
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyImageView(VkContext.vkDevice(), sv, null);
            for (long v : mv) {
                VK12.vkDestroyImageView(VkContext.vkDevice(), v, null);
            }
            Vma.vmaDestroyImage(vmaHandle, im, al);
        });
        image = 0L;
        allocation = 0L;
        sampledView = 0L;
        mipViews = new long[0];
    }

    public void delete() {
        destroyResources();
        width = -1;
        height = -1;
    }
}
