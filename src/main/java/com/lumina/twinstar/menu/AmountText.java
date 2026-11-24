package com.lumina.twinstar.menu;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import com.lumina.corelib.util.BigAmountFormat;

import java.math.BigInteger;

/** An amount of a key as text: AE2's own format up to a long, ours beyond. */
public final class AmountText {
    private AmountText() {
    }

    /** {@code full}: every digit (tooltips); otherwise the short slot form. */
    public static String format(AEKey what, BigInteger v, boolean full) {
        if (BigAmountFormat.fitsLong(v))
            return what.formatAmount(v.longValue(), full ? AmountFormat.FULL : AmountFormat.SLOT);
        String unit = what.getType().getUnitSymbol();
        int perUnit = what.getType().getAmountPerUnit();
        return full ? BigAmountFormat.fullForm(v, perUnit, unit == null ? "" : unit)
                : BigAmountFormat.shortForm(v, perUnit, unit == null ? "" : unit);
    }
}
