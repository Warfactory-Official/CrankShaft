package dev.engine_room.flywheel.backend.mixin.mod.optifine;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.shaderpack.DeferredReplayCompilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.OptifineIntegration;
import dev.engine_room.flywheel.backend.engine.shaderpack.OptifineReplayBridge;
import net.optifine.shaders.IShaderPack;
import net.optifine.shaders.Program;
import net.optifine.shaders.Shaders;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value = Shaders.class, remap = false)
public abstract class ShaderHostMixin {
    @Inject(method = "useProgram", at = @At("RETURN"), require = 1)
    private static void flw$captureEnabled(Program program, CallbackInfo ci) {
        OptifineReplayBridge.bindProgram(program);
    }

    // Keep OptiFine's native nearest-pass loop, including its custom-texture lifetime and output flips.
    @WrapMethod(method = "renderComposites")
    private static void flw$materialComposites(Program[] programs, boolean finalOutput, Operation<Void> original) {
        var scope = OptifineReplayBridge.composites(programs, finalOutput);
        if (scope == null) {
            original.call(programs, finalOutput);
            return;
        }
        try (scope) {
            original.call(programs, finalOutput);
            scope.completed();
        }
    }

    // The native fullscreen state and family texture overrides are ready immediately before the first program.
    @WrapOperation(method = "renderComposites", at = @At(value = "INVOKE",
            target = "Lnet/optifine/shaders/Shaders;useProgram(Lnet/optifine/shaders/Program;)V"), require = 1)
    private static void flw$beforeMaterialProgram(Program program, Operation<Void> original) {
        OptifineReplayBridge.beforeProgram(program);
        original.call(program);
    }

    // Finish material replay before the final resolve sees its ordinary native inputs.
    @Inject(method = "renderFinal", at = @At("HEAD"), require = 1)
    private static void flw$restoreMaterialInputs(CallbackInfo ci) {
        OptifineReplayBridge.beforeFinal();
    }

    @Inject(method = "drawCompositeQuad", at = @At("HEAD"), cancellable = true, require = 1)
    private static void flw$layerDraw(CallbackInfo ci) {
        if (OptifineReplayBridge.graphics()) ci.cancel();
    }

    // The native compiler resolves includes and options before handing the stage to OpenGL.
    @WrapMethod(method = {"createVertShader", "createFragShader", "createGeomShader"})
    private static int flw$compileScope(Program program, String filename, Operation<Integer> original) {
        try (var scope = OptifineIntegration.enterCompilation(program)) {
            return original.call(program, filename);
        }
    }

    // Material ABI changes must see the host's final include/option expansion.
    @ModifyArgs(method = {"createVertShader", "createFragShader", "createGeomShader"}, at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBShaderObjects;glShaderSourceARB(ILjava/lang/CharSequence;)V"), require = 3)
    private static void flw$materialReplay(Args args) {
        if (!DeferredReplayCompilation.active()) return;
        var shader = (int) args.get(0);
        var source = ((CharSequence) args.get(1)).toString();
        args.set(1, DeferredReplayCompilation.transform(source, GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE)));
    }

    @Inject(method = "loadShaderPack", at = @At("HEAD"), require = 1)
    private static void flw$reloadStarted(CallbackInfo ci) {
        OptifineIntegration.reloadStarted();
    }

    // Option changes and dimension changes can rebuild programs without selecting another pack.
    @Inject(method = "uninit", at = @At("HEAD"), require = 1)
    private static void flw$programsInvalidated(CallbackInfo ci) {
        OptifineIntegration.programsInvalidated();
    }

    @Inject(method = "resourcesReloaded", at = @At("RETURN"), require = 1)
    private static void flw$aliasesReloaded(CallbackInfo ci) {
        OptifineIntegration.aliasesReloaded();
    }

    // Pack properties/options must see the same source overlay as native compilation and guest programs.
    @ModifyReturnValue(method = "getShaderPack(Ljava/lang/String;)Lnet/optifine/shaders/IShaderPack;", at = @At("RETURN"), require = 1)
    private static IShaderPack flw$patch(IShaderPack pack) {
        return OptifineIntegration.patch(pack);
    }

    @Inject(method = "loadShaderPack", at = @At("RETURN"), require = 1)
    private static void flw$reloadFinished(CallbackInfo ci) {
        OptifineIntegration.reloadFinished();
    }

    // Native linking deletes the shader objects, before a guest renderer can inspect the linked program.
    @Inject(method = {"createVertShader", "createFragShader", "createGeomShader"}, at = @At("RETURN"), require = 3)
    private static void flw$capture(Program program, String filename, CallbackInfoReturnable<Integer> cir) {
        OptifineIntegration.capture(program, cir.getReturnValueI());
    }

    // Reuse native uniform uploads without replacing the guest pass's indexed blending.
    @Inject(method = "updateAlphaBlend", at = @At("HEAD"), cancellable = true, require = 1)
    private static void flw$guestBlend(Program previous, Program next, CallbackInfo ci) {
        if (OptifineIntegration.uploadingGuest) ci.cancel();
    }
}
