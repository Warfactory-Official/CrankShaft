package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jspecify.annotations.Nullable;

import java.util.*;

@SideOnly(Side.CLIENT)
public final class CommandFlywheel extends CommandBase implements IClientCommand {

    private static final List<String> TOP_LEVEL = List.of("backend", "smoothness", "limitupdates", "debug", "list", "toggle", "reload");
    private static final List<String> DEBUG_SUBS = List.of("shader", "crumbling", "frustum", "pauseUpdates", "lightSections", "info");
    private static final List<String> ON_OFF_SUBS = List.of("on", "off");
    private static final List<String> FRUSTUM_SUBS = List.of("capture", "unpause");
    private static final List<String> TOGGLE_STATES = List.of("on", "off");
    private static final List<String> ON_OFF = List.of("on", "off");
    // DebugMode.values() clones on every call; cache once.
    private static final DebugMode[] DEBUG_MODES = DebugMode.values();
    private static final LightSmoothness[] LIGHT_SMOOTHNESS_VALUES =
            LightSmoothness.values();

    @Override public String getName() { return "flywheel"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }
    @Override public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) { return false; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/flywheel <backend|smoothness|limitupdates|debug|list|toggle|reload>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, getUsage(sender));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "backend":      handleBackend(sender, tail(args));      break;
            case "smoothness":   handleSmoothness(sender, tail(args));   break;
            case "limitupdates": handleLimitUpdates(sender, tail(args)); break;
            case "debug":        handleDebug(sender, tail(args));        break;
            case "list":         handleList(sender);                     break;
            case "toggle":       handleToggle(sender, tail(args));       break;
            case "reload":       handleReload(sender);                   break;
            default:             send(sender, "Unknown subcommand: " + args[0]);
        }
    }

    private static void handleBackend(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "Backend: " + BackendManagerImpl.getBackendString()
                    + " (default: " + BackendManagerImpl.getBackendString(BackendManager.defaultBackend()) + ")");
            return;
        }
        String id = args[0];
        Backend requested = resolveBackendArg(id);
        if (requested == null) {
            send(sender, "Unknown backend: " + id + " (try DEFAULT or a registered backend id like flywheel:indirect)");
            return;
        }
        if (requested != BackendManager.offBackend() && !requested.isSupported()) {
            send(sender, "Backend '" + id + "' reports unsupported on this system; not switching.");
            return;
        }
        Backend prev = BackendManager.currentBackend();
        if (prev == requested) {
            send(sender, "Backend already: " + BackendManagerImpl.getBackendString(requested));
            return;
        }
        // only trigger visualization rebuild on actual backend changes, not on every loadRenderers() callsite
        FlwConfig.INSTANCE.setBackendString(id.equalsIgnoreCase(FlwConfig.DEFAULT_BACKEND_STR) ? FlwConfig.DEFAULT_BACKEND_STR : id);
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
        BackendManagerImpl.onReloadLevelRenderer(Minecraft.getMinecraft().world);
        send(sender, "Backend: " + BackendManagerImpl.getBackendString(prev) + " -> " + BackendManagerImpl.getBackendString());
    }

    private static @Nullable Backend resolveBackendArg(String id) {
        if (id.equalsIgnoreCase(FlwConfig.DEFAULT_BACKEND_STR)) {
            return BackendManager.defaultBackend();
        }
        // Allow bare ids ("indirect") by prefixing the flywheel namespace; full ids ("foo:bar") pass through.
        String full = id.contains(":") ? id : Flywheel.ID + ":" + id;
        try {
            return Backend.REGISTRY.get(new ResourceLocation(full));
        } catch (Exception e) {
            return null;
        }
    }

    private static void handleSmoothness(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("/flywheel smoothness <");
            for (int i = 0; i < LIGHT_SMOOTHNESS_VALUES.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(LIGHT_SMOOTHNESS_VALUES[i].name().toLowerCase(Locale.ROOT));
            }
            sb.append(">  (current: ").append(FlwConfig.INSTANCE.lightSmoothness().name().toLowerCase(Locale.ROOT)).append(')');
            send(sender, sb.toString());
            return;
        }
        LightSmoothness target;
        try {
            target = LightSmoothness.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, "Unknown smoothness: " + args[0]);
            return;
        }
        if (FlwConfig.INSTANCE.lightSmoothness() == target) {
            send(sender, "Smoothness already: " + target.name().toLowerCase(Locale.ROOT));
            return;
        }
        FlwConfig.INSTANCE.setLightSmoothness(target);
        FlwPrograms.reload(Minecraft.getMinecraft().getResourceManager());
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
        send(sender, "Smoothness: " + target.name().toLowerCase(Locale.ROOT));
    }

    private static void handleLimitUpdates(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel limitupdates <on|off>  (current: " + (FlwConfig.INSTANCE.limitUpdates() ? "on" : "off") + ")");
            return;
        }
        String s = args[0].toLowerCase(Locale.ROOT);
        boolean target;
        if (s.equals("on")) target = true;
        else if (s.equals("off")) target = false;
        else {
            send(sender, "Must be 'on' or 'off': " + args[0]);
            return;
        }
        FlwConfig.INSTANCE.setLimitUpdates(target);
        // Mirrors upstream: /flywheel limitUpdates calls levelRenderer.allChanged() to apply the
        // new limiter shape (BandedPrimeLimiter vs NonLimiter), which is decided at manager init.
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
        BackendManagerImpl.onReloadLevelRenderer(Minecraft.getMinecraft().world);
        send(sender, "Limit updates: " + (target ? "on" : "off"));
    }

    private static void handleDebug(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel debug <shader|crumbling|frustum|pauseUpdates|lightSections|info>");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "shader":        handleDebugShader(sender, tail(args));        break;
            case "crumbling":     handleDebugCrumbling(sender, tail(args));     break;
            case "frustum":       handleDebugFrustum(sender, tail(args));       break;
            case "pauseupdates":  handleDebugPauseUpdates(sender, tail(args));  break;
            case "lightsections": handleDebugLightSections(sender, tail(args)); break;
            case "info":          handleDebugInfo(sender);                      break;
            default:              send(sender, "Unknown debug subcommand: " + args[0]);
        }
    }

    private static void handleDebugShader(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("/flywheel debug shader <");
            for (int i = 0; i < DEBUG_MODES.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(DEBUG_MODES[i].getSerializedName());
            }
            sb.append('>');
            send(sender, sb.toString());
            return;
        }
        DebugMode mode = parseDebugMode(args[0]);
        if (mode == null) {
            send(sender, "Unknown shader debug mode: " + args[0]);
            return;
        }
        FrameUniforms.debugMode(mode);
        send(sender, "Shader debug mode set to: " + mode.getSerializedName());
    }

    private static void handleDebugCrumbling(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel debug crumbling <stage 0-9>");
            return;
        }
        int stage;
        try { stage = Integer.parseInt(args[0]); }
        catch (NumberFormatException e) {
            send(sender, "Invalid stage (expected 0-9): " + args[0]);
            return;
        }
        if (stage < 0 || stage > 9) {
            send(sender, "Stage out of range (0-9): " + stage);
            return;
        }
        if (!(sender instanceof EntityPlayer player)) {
            send(sender, "Player only.");
            return;
        }
        RayTraceResult trace = player.rayTrace(20.0D, 1.0F);
        if (trace == null || trace.typeOfHit != RayTraceResult.Type.BLOCK) {
            send(sender, "Look at a block first.");
            return;
        }
        BlockPos pos = trace.getBlockPos();
        player.world.sendBlockBreakProgress(player.getEntityId(), pos, stage);
        send(sender, "Crumbling stage " + stage + " applied at " + pos);
    }

    private static void handleDebugFrustum(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel debug frustum <capture|unpause>");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "capture":
                FrameUniforms.captureFrustum();
                send(sender, "Frustum captured; cull frustum is now frozen at current view.");
                break;
            case "unpause":
                FrameUniforms.unpauseFrustum();
                send(sender, "Frustum unpaused.");
                break;
            default:
                send(sender, "Unknown frustum subcommand: " + args[0]);
        }
    }

    private static void handleDebugPauseUpdates(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel debug pauseUpdates <on|off>  (current: " + (ImplDebugFlags.PAUSE_UPDATES ? "on" : "off") + ")");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on":
                ImplDebugFlags.PAUSE_UPDATES = true;
                send(sender, "Visual updates paused.");
                break;
            case "off":
                ImplDebugFlags.PAUSE_UPDATES = false;
                send(sender, "Visual updates resumed.");
                break;
            default:
                send(sender, "Unknown pauseUpdates subcommand: " + args[0]);
        }
    }

    private static void handleDebugLightSections(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel debug lightSections <on|off>  (current: " + (BackendDebugFlags.LIGHT_STORAGE_VIEW ? "on" : "off") + ")");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on":
                BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
                send(sender, "Light section debug overlay enabled.");
                break;
            case "off":
                BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
                send(sender, "Light section debug overlay disabled.");
                break;
            default:
                send(sender, "Unknown lightSections subcommand: " + args[0]);
        }
    }

    private static void handleDebugInfo(ICommandSender sender) {
        sender.sendMessage(FlwDebugInfo.getDebugCommandInfo());
    }

    private static void handleList(ICommandSender sender) {
        Set<Class<?>> registered = VisualizerRegistry.registered();
        if (registered.isEmpty()) {
            send(sender, "No visualizers registered.");
            return;
        }
        List<Class<?>> sorted = new ArrayList<>(registered);
        sorted.sort(Comparator.comparing(Class::getSimpleName));
        send(sender, "Registered visualizers (" + sorted.size() + "):");
        for (Class<?> type : sorted) {
            send(sender, "  " + (VisualizerRegistry.isEnabled(type) ? "[on]  " : "[off] ") + type.getSimpleName());
        }
    }

    private static void handleToggle(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "/flywheel toggle <visualizer-class> [on|off]");
            return;
        }
        Class<?> match = findMatch(args[0]);
        if (match == null) {
            send(sender, "No registered visualizer matches: " + args[0]);
            return;
        }
        boolean target;
        if (args.length >= 2) {
            String state = args[1].toLowerCase(Locale.ROOT);
            if (state.equals("on")) target = true;
            else if (state.equals("off")) target = false;
            else {
                send(sender, "State must be 'on' or 'off': " + args[1]);
                return;
            }
        } else {
            target = !VisualizerRegistry.isEnabled(match);
        }
        VisualizerRegistry.setEnabled(match, target);
        send(sender, match.getSimpleName() + " -> " + (target ? "enabled" : "disabled")
                + ". Use /flywheel reload to flush existing visuals.");
    }

    private static void handleReload(ICommandSender sender) {
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
        BackendManagerImpl.onReloadLevelRenderer(Minecraft.getMinecraft().world);
        send(sender, "Renderers reloaded.");
    }

    private static DebugMode parseDebugMode(String name) {
        String norm = name.toLowerCase(Locale.ROOT);
        for (DebugMode m : DEBUG_MODES) {
            if (m.getSerializedName().equals(norm)) return m;
        }
        return null;
    }

    private static @Nullable Class<?> findMatch(String query) {
        String norm = query.toLowerCase(Locale.ROOT);
        Class<?> hit = null;
        for (Class<?> type : VisualizerRegistry.registered()) {
            if (type.getSimpleName().toLowerCase(Locale.ROOT).endsWith(norm)) {
                if (hit != null) return null; // multiple hits; require disambiguation
                hit = type;
            }
        }
        return hit;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, TOP_LEVEL);
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("debug"))        return getListOfStringsMatchingLastWord(args, DEBUG_SUBS);
            if (args[0].equalsIgnoreCase("toggle"))       return getListOfStringsMatchingLastWord(args, classNameList());
            if (args[0].equalsIgnoreCase("backend"))      return getListOfStringsMatchingLastWord(args, backendIds());
            if (args[0].equalsIgnoreCase("smoothness"))   return getListOfStringsMatchingLastWord(args, lightSmoothnessNames());
            if (args[0].equalsIgnoreCase("limitupdates")) return getListOfStringsMatchingLastWord(args, ON_OFF);
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("shader")) {
                List<String> modes = new ArrayList<>();
                for (DebugMode m : DEBUG_MODES) modes.add(m.getSerializedName());
                return getListOfStringsMatchingLastWord(args, modes);
            }
            if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("frustum")) {
                return getListOfStringsMatchingLastWord(args, FRUSTUM_SUBS);
            }
            if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("pauseUpdates")) {
                return getListOfStringsMatchingLastWord(args, ON_OFF_SUBS);
            }
            if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("lightSections")) {
                return getListOfStringsMatchingLastWord(args, ON_OFF_SUBS);
            }
            if (args[0].equalsIgnoreCase("toggle")) {
                return getListOfStringsMatchingLastWord(args, TOGGLE_STATES);
            }
        }
        return Collections.emptyList();
    }

    private static List<String> classNameList() {
        List<String> out = new ArrayList<>();
        for (Class<?> type : VisualizerRegistry.registered()) out.add(type.getSimpleName());
        return out;
    }

    private static List<String> backendIds() {
        List<String> out = new ArrayList<>();
        out.add(FlwConfig.DEFAULT_BACKEND_STR);
        for (Backend b : Backend.REGISTRY) {
            ResourceLocation id = Backend.REGISTRY.getId(b);
            if (id == null) continue;
            // Surface the bare path for the common flywheel: namespace; full id for foreign namespaces.
            if (Flywheel.ID.equals(id.getNamespace())) {
                out.add(id.getPath());
            } else {
                out.add(id.toString());
            }
        }
        return out;
    }

    private static List<String> lightSmoothnessNames() {
        List<String> out = new ArrayList<>(LIGHT_SMOOTHNESS_VALUES.length);
        for (LightSmoothness v : LIGHT_SMOOTHNESS_VALUES) {
            out.add(v.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) return new String[0];
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    private static void send(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}
