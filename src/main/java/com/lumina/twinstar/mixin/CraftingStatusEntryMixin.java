package com.lumina.twinstar.mixin;

import appeng.menu.me.crafting.CraftingStatusEntry;
import com.lumina.twinstar.menu.BigStatusAmounts;
import com.lumina.twinstar.menu.BigStatusEntry;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The exact amounts ride after AE2's own fields (one flag byte when there are none). */
@Mixin(value = CraftingStatusEntry.class, remap = false)
public abstract class CraftingStatusEntryMixin implements BigStatusEntry {
    @Unique
    private @Nullable BigStatusAmounts twinstar$big;

    @Override
    public @Nullable BigStatusAmounts twinstar$big() {
        return twinstar$big;
    }

    @Override
    public void twinstar$setBig(@Nullable BigStatusAmounts big) {
        this.twinstar$big = big;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void twinstar$writeBig(FriendlyByteBuf buf, CallbackInfo ci) {
        buf.writeBoolean(twinstar$big != null);
        if (twinstar$big != null)
            twinstar$big.write(buf);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void twinstar$readBig(FriendlyByteBuf buf, CallbackInfoReturnable<CraftingStatusEntry> cir) {
        if (buf.readBoolean())
            ((BigStatusEntry) cir.getReturnValue()).twinstar$setBig(BigStatusAmounts.read(buf));
    }
}
