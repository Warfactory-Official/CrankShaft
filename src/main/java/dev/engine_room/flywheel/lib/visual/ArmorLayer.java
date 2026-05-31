package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link LivingLayer} that renders a biped entity's worn armor as instanced {@code ModelBiped} geometry,
 * including leather dye + overlay and the enchantment glint.
 *
 * <p>Register from a biped visual's constructor above the body/held item, e.g.
 * {@code addLayer(new ArmorLayer(ctx, entity, instances, 2))}. Vanilla uses an OUTER {@code ModelBiped(1.0)}
 * (head/chest/feet, {@code *_layer_1.png}) and a thinner INNER {@code ModelBiped(0.5)} (legs,
 * {@code *_layer_2.png}). This keeps one {@link InstanceTree} per render target — base texture, leather
 * {@code _overlay} texture, and the two shared glint passes per inflation — copies the body's posed bones
 * node-for-node, and masks each tree's bones per equipped slot via {@link InstanceTree#skipDraw}.
 *
 * <p>Per-instance dye is applied per bone (the disjoint slot masks let one shared tree carry a different dye
 * for head vs chest vs feet). Armor materials are shared statically so identical armor batches across
 * entities. Custom Forge (non-{@code ModelBiped}) armor models are not yet handled — they render with the
 * default biped shape. The enchant glint uses dedicated armor-glint shaders ({@code Materials.GLINT_ARMOR})
 * matching vanilla {@code LayerArmorBase.renderEnchantedGlint} (the scroll is global-clock rather than
 * per-entity-age, an imperceptible phase difference). All per-entity state is instance-local
 * ({@code beginFrame} is concurrent).
 */
public final class ArmorLayer implements LivingLayer {
    // Bone-index masks against BipedEntityModel.roots order: [0]head [1]body [2]rArm [3]lArm [4]rLeg [5]lLeg [6]headwear.
    private static final int HEAD = (1 << 0) | (1 << 6);
    private static final int CHEST = (1 << 1) | (1 << 2) | (1 << 3);
    private static final int LEGS = (1 << 1) | (1 << 4) | (1 << 5);
    private static final int FEET = (1 << 4) | (1 << 5);
    private static final EntityEquipmentSlot[] ARMOR_SLOTS = ArmorModels.ARMOR_SLOTS;
    private static final int[] SLOT_BONES = { HEAD, CHEST, LEGS, FEET };
    private static final ArmorTree[] NO_TREES = {};
    private static final int ROOT_COUNT = 7;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Map<String, Material> MATERIALS = new ConcurrentHashMap<>();

    private final InstancerProvider instancers;
    private final EntityLivingBase entity;
    private final InstanceTree body;
    private final int bias;

    // The map dedups by key on (rare) stack changes; the array is what the per-frame loops walk.
    private final Map<String, ArmorTree> trees = new HashMap<>();
    private ArmorTree[] treeList = NO_TREES;
    private final ItemStack[] lastStack = { ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
    private final String[] baseTex = new String[4];
    private final String[] overlayTex = new String[4];
    private final int[] dye = new int[4];
    private final boolean[] glint = new boolean[4];
    // Resolved per slot when the stack changes, so the per-frame path never rebuilds the String tree keys.
    private final ArmorTree[] baseTree = new ArmorTree[4];
    private final ArmorTree[] overlayTree = new ArmorTree[4];
    private final ArmorTree[] glintTree = new ArmorTree[4];
    private final ArmorTree[] glint2Tree = new ArmorTree[4];
    private int lastLight = Integer.MIN_VALUE;
    private boolean visible = true;

    public ArmorLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body, int bias) {
        this.instancers = ctx.instancerProvider();
        this.entity = entity;
        this.body = body;
        this.bias = bias;
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (!visible) {
            return;
        }

        for (ArmorTree t : treeList) {
            t.frameMask = 0;
        }

        boolean dirty = bodyMoved || light != lastLight;
        lastLight = light;

        for (int s = 0; s < ARMOR_SLOTS.length; s++) {
            EntityEquipmentSlot slot = ARMOR_SLOTS[s];
            ItemStack stack = entity.getItemStackFromSlot(slot);
            if (!(stack.getItem() instanceof ItemArmor) || ((ItemArmor) stack.getItem()).getEquipmentSlot() != slot) {
                lastStack[s] = ItemStack.EMPTY;
                continue;
            }
            ItemArmor armor = (ItemArmor) stack.getItem();
            if (!ItemStack.areItemStacksEqual(stack, lastStack[s])) {
                lastStack[s] = stack.copy();
                boolean dyed = armor.hasOverlay(stack);
                baseTex[s] = armorTexture(armor, stack, slot, null);
                overlayTex[s] = dyed ? armorTexture(armor, stack, slot, "overlay") : null;
                dye[s] = dyed ? (0xFF000000 | armor.getColor(stack)) : WHITE;
                glint[s] = stack.hasEffect();
                // Resolve (and lazily create) the trees only on a stack change. Key by inflation too: a modded
                // getArmorTexture could return one path for a leg slot (0.5) and a non-leg slot (1.0); a
                // texture-only key would reuse the first slot's inflation.
                float inflation = slot == EntityEquipmentSlot.LEGS ? 0.5F : 1.0F;
                baseTree[s] = tree(inflation + "|" + baseTex[s], armorMaterial(baseTex[s]), inflation, bias);
                overlayTree[s] = overlayTex[s] != null
                        ? tree(inflation + "|" + overlayTex[s], armorMaterial(overlayTex[s]), inflation, bias) : null;
                // Glint: two passes over byte-identical geometry at a higher bias (drawn after the base writes
                // depth; GLINT uses depthTest=EQUAL), keyed by inflation so enchanted pieces share one glint tree.
                glintTree[s] = glint[s] ? tree("glint:" + inflation, Materials.GLINT_ARMOR, inflation, bias + 1) : null;
                glint2Tree[s] = glint[s] ? tree("glint2:" + inflation, Materials.GLINT_ARMOR_2, inflation, bias + 1) : null;
                dirty = true;
            }

            int bones = SLOT_BONES[s];
            setBones(baseTree[s], bones, dye[s]);
            if (overlayTree[s] != null) {
                setBones(overlayTree[s], bones, WHITE);
            }
            if (glintTree[s] != null) {
                setBones(glintTree[s], bones, WHITE);
                setBones(glint2Tree[s], bones, WHITE);
            }
        }

        boolean maskChanged = false;
        for (ArmorTree t : treeList) {
            if (t.frameMask != t.appliedMask) {
                applyMask(t.tree, t.frameMask);
                t.appliedMask = t.frameMask;
                maskChanged = true;
            }
        }

        // Body idle, same light, no equip/dye/mask change ⇒ the pose pushed last update still matches the body;
        // skip the per-bone re-upload (mirrors the body's own idle-frame dirty tracking). A mask change still
        // pushes, since un-skipping reseeds the slot (see InstanceTree#skipDraw).
        if (!dirty && !maskChanged) {
            return;
        }

        for (ArmorTree t : treeList) {
            if (t.frameMask == 0) {
                continue;
            }
            // Armor is always NO_OVERLAY — vanilla never red-flashes the armor layer
            // (LayerArmorBase.shouldCombineTextures()==false, so RenderLivingBase.setBrightness skips it).
            for (int i = 0; i < ROOT_COUNT; i++) {
                if ((t.frameMask & (1 << i)) != 0) {
                    TransformedInstance inst = t.tree.child(i).instance();
                    if (inst != null) {
                        inst.colorArgb(t.boneColor[i]);
                        inst.light(light);
                        inst.overlay(OverlayTexture.NO_OVERLAY);
                    }
                }
            }
            t.tree.copyComposedFrom(body);
        }
    }

    private static void setBones(ArmorTree t, int bones, int color) {
        t.frameMask |= bones;
        for (int i = 0; i < ROOT_COUNT; i++) {
            if ((bones & (1 << i)) != 0) {
                t.boneColor[i] = color;
            }
        }
    }

    private ArmorTree tree(String key, Material material, float inflation, int treeBias) {
        ArmorTree t = trees.get(key);
        if (t == null) {
            String cacheKey = "flywheel:armor:" + key;
            EntityModel<ModelBiped> model = new BipedEntityModel<>(() -> new ModelBiped(inflation));
            InstanceTree it = InstanceTree.create(instancers,
                    AbstractLivingEntityVisual.buildTree(model, material, cacheKey), treeBias);
            t = new ArmorTree(it);
            trees.put(key, t);
            ArmorTree[] grown = Arrays.copyOf(treeList, treeList.length + 1);
            grown[treeList.length] = t;
            treeList = grown;
        }
        return t;
    }

    private static Material armorMaterial(String texture) {
        return MATERIALS.computeIfAbsent(texture, t -> SimpleMaterial.builderOf(Materials.CUTOUT_NO_CULL)
                .cardinalLightingMode(CardinalLightingMode.ENTITY)
                .texture(new ResourceLocation(t))
                .mipmap(false)
                .build());
    }

    private void applyMask(InstanceTree tree, int mask) {
        for (int i = 0; i < ROOT_COUNT; i++) {
            tree.child(i).skipDraw((mask & (1 << i)) == 0);
        }
    }

    // 1.12.2: hand-port of LayerArmorBase.getArmorResource, then the Forge override hook.
    private String armorTexture(ItemArmor armor, ItemStack stack, EntityEquipmentSlot slot, @Nullable String type) {
        String matName = armor.getArmorMaterial().getName();
        String domain = "minecraft";
        int colon = matName.indexOf(':');
        if (colon != -1) {
            domain = matName.substring(0, colon);
            matName = matName.substring(colon + 1);
        }
        int layer = slot == EntityEquipmentSlot.LEGS ? 2 : 1;
        String suffix = type == null ? "" : "_" + type;
        String path = String.format("%s:textures/models/armor/%s_layer_%d%s.png", domain, matName, layer, suffix);
        return ForgeHooksClient.getArmorTexture(entity, stack, path, slot, type);
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            for (ArmorTree t : treeList) {
                if (t.appliedMask != 0) {
                    applyMask(t.tree, 0);
                    t.appliedMask = 0;
                }
            }
        }
    }

    @Override
    public void delete() {
        for (ArmorTree t : treeList) {
            t.tree.delete();
        }
        trees.clear();
        treeList = NO_TREES;
    }

    private static final class ArmorTree {
        final InstanceTree tree;
        final int[] boneColor = new int[ROOT_COUNT];
        int frameMask;
        int appliedMask;

        ArmorTree(InstanceTree tree) {
            this.tree = tree;
        }
    }
}
