package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.CommonCrumbling;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.embed.RenderIdentity;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.BlockStateMesh;
import dev.engine_room.flywheel.lib.model.ItemTaggedModel;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

final class GuestDrawMetadata {
    private static final ResourceLocation CURRENT_PLAYER = new ResourceLocation("minecraft", "current_player");
    private final RenderIdentity identity;
    private final boolean borrowedBlock;
    private final List<PackIdentity> identities;
    private final ItemStack item;
    private final Material material;
    int borrowedRenderType;
    int borrowedMetadata;
    int borrowedEmission;
    private GuestHost owner;
    private int tag;
    private int itemTag;
    private ResourceLocation entityKey;
    private Material damageMaterial;

    GuestDrawMetadata(GroupKey<?> group, Material material, Mesh mesh, Model model) {
        this.material = material;
        identity = group.environment().identity();
        var block = BlockStateMesh.find(mesh);
        var borrowed = false;
        if (identity == null && block != null) {
            for (var vertex = 0; vertex < mesh.vertexCount(); vertex += 4)
                if (block.blockState(vertex) != null) {
                    borrowed = true;
                    break;
                }
        }
        borrowedBlock = borrowed;
        var offered = new ArrayList<PackIdentity>();
        var stack = ItemStack.EMPTY;
        while (true) {
            if (model instanceof PackTaggedModel tagged) {
                offered.addAll(tagged.identities());
                model = tagged.delegate();
            } else if (model instanceof ItemTaggedModel tagged) {
                stack = new ItemStack(tagged.item(), 1, tagged.metadata());
                model = tagged.delegate();
            } else break;
        }
        identities = List.copyOf(offered);
        item = stack;
    }

    int tag(GuestHost host) {
        if (owner == host) return tag;
        borrowedRenderType = borrowedMetadata = borrowedEmission = 0;
        var resolved = 0;
        var renderedItem = 0;
        var key = identity == null ? null : identity.entity();
        if (identity != null) {
            var id = 0;
            if (identity.block() != null) id = host.blockId(identity.block());
            else if (identity.entity() != null) {
                var mappedKey = identity.localPlayer() && host.entityIds().containsKey(
                        CURRENT_PLAYER) ? CURRENT_PLAYER : identity.entity();
                id = host.entityIds().getOrDefault(mappedKey, 0);
            }
            resolved = TaggedEnvironment.tag(identity.kind(), id);
        } else if (borrowedBlock) resolved = TaggedEnvironment.tag(TaggedEnvironment.KIND_BORROWED_BLOCK, -1);
        var selected = false;
        for (var offered : identities) {
            if (offered instanceof PackIdentity.OfItem itemIdentity) {
                if (renderedItem != 0) continue;
                var actual = Item.getByNameOrId(itemIdentity.item().toString());
                if (actual != null) {
                    var id = host.itemId(new ItemStack(actual));
                    if (id != 0) renderedItem = TaggedEnvironment.tag(TaggedEnvironment.KIND_ITEM, id);
                }
                continue;
            }
            if (selected) continue;
            if (offered instanceof PackIdentity.OfBlock block) {
                var state = block.state();
                var id = host.blockId(state);
                if (id != 0) {
                    resolved = TaggedEnvironment.tag(TaggedEnvironment.KIND_BORROWED_BLOCK, id);
                    borrowedRenderType = state.getRenderType().ordinal();
                    borrowedMetadata = state.getBlock().getMetaFromState(state);
                    borrowedEmission = state.getLightValue();
                    key = null;
                    selected = true;
                }
            } else if (offered instanceof PackIdentity.OfEntity entity) {
                var id = host.entityIds().getOrDefault(entity.entity(), 0);
                if (id != 0) {
                    resolved = TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, id);
                    key = entity.entity();
                    selected = true;
                }
            } else if (offered instanceof PackIdentity.OfProgram program) {
                var kind = switch (program.program()) {
                    case "spidereyes" -> TaggedEnvironment.KIND_ENTITY_EYES;
                    case "entities_translucent" -> TaggedEnvironment.KIND_ENTITY_TRANSLUCENT;
                    case "entities" -> TaggedEnvironment.KIND_ENTITY_BLENDED;
                    default -> throw new IllegalArgumentException(
                            "Unknown shaderpack program identity " + program.program());
                };
                if (program.entity() != null || TaggedEnvironment.isEntity(resolved)) {
                    var id = program.entity() == null ? TaggedEnvironment.id(resolved) : host.entityIds().getOrDefault(
                            program.entity(), 0);
                    resolved = TaggedEnvironment.tag(kind, id);
                    if (program.entity() != null) key = program.entity();
                    selected = true;
                }
            }
        }
        if (renderedItem == 0 && !item.isEmpty()) {
            if (item.getItem() instanceof ItemBlock block) {
                renderedItem = TaggedEnvironment.tag(TaggedEnvironment.KIND_BLOCK_ITEM,
                        host.blockId(block.getBlock().getStateFromMeta(item.getMetadata())));
            } else renderedItem = TaggedEnvironment.tag(TaggedEnvironment.KIND_ITEM, host.itemId(item));
        }
        tag = resolved;
        itemTag = renderedItem;
        entityKey = key;
        owner = host;
        return tag;
    }

    int kind(GuestHost host) {
        return TaggedEnvironment.kind(tag(host));
    }

    int itemTag(GuestHost host) {
        tag(host);
        return itemTag;
    }

    ResourceLocation entityKey() {
        return entityKey;
    }

    Material material(boolean crumbling) {
        if (!crumbling) return material;
        if (damageMaterial == null) {
            var builder = SimpleMaterial.builder();
            CommonCrumbling.applyCrumblingProperties(builder, material);
            damageMaterial = builder.build();
        }
        return damageMaterial;
    }

    boolean castsShadow(GuestHost host) {
        var transparency = material.transparency();
        if (transparency == Transparency.ADDITIVE || transparency == Transparency.LIGHTNING) return false;
        if (transparency == Transparency.GLINT && !host.contracts().has(ContractProgram.SHADOW_GLINT)) return false;
        var kind = kind(host);
        var settings = host.settings();
        if (kind == TaggedEnvironment.KIND_BORROWED_BLOCK) return true;
        if (TaggedEnvironment.isEntity(tag(host))) return identity != null && identity.localPlayer()
                ? settings.shadowPlayer() : settings.shadowEntities();
        if (kind == TaggedEnvironment.KIND_BLOCK_ENTITY) return settings.shadowBlockEntities()
                || settings.shadowLightBlockEntities() && identity.block().getLightValue() > 0;
        return true;
    }
}
