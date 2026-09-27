package pangch.pangchProfileToBlockMod.mixin.client;

import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pangch.pangchProfileToBlockMod.client.ProfilerCapture;

@Mixin(Profiler.class)
public class ProfilerMixin {
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private static void pangch$capture(CallbackInfoReturnable<ProfilerFiller> cir) {
        cir.setReturnValue(ProfilerCapture.attach(cir.getReturnValue()));
    }
}
