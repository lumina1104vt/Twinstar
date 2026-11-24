package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.order.PlanView;
import com.lumina.corelib.util.BigAmountFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the plan screen, the order screen and the crafting graphs share: sizes, colours, and the words and numbers they write
 * (the screens themselves are {@code PlanScreen} and {@code OrderScreen}, drawn with {@link UiKit}).
 */
public final class PlanUI {
    public static final int WIDTH = 262, HEIGHT = 214;

    public static final int TEXT = UiKit.TEXT;
    public static final int DIM = UiKit.DIM;
    public static final int CRAFT = 0xFF96ACFF;
    public static final int GOOD = UiKit.GOOD;
    public static final int BAD = UiKit.BAD;
    public static final int AVOID_COLOR = 0xFFF0B060;
    public static final int KEEP_COLOR = 0xFF5AB4FF;
    public static final int LOOP_COLOR = 0xFFC890FF;

    private PlanUI() {
    }

    /** A style that only sets the colour (1.20.1 has no {@code Component.withColor}). */
    public static Style colorStyle(int argb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF));
    }

    /** A component in one colour. */
    public static MutableComponent coloured(MutableComponent c, int argb) {
        return c.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
    }

    /** Where a row's amount comes from: crafted / stock / infinite source / missing, coloured. */
    public static Component sources(PlanView.Row row) {
        List<MutableComponent> parts = new ArrayList<>();
        if (row.shared())
            parts.add(coloured(Component.translatable("gui.twinstar.plan.shared"), DIM));
        if (!row.runs().isEmpty())
            parts.add(coloured(Component.translatable("gui.twinstar.plan.runs", shortText(row.runs(), null)), CRAFT));
        if (!row.stock().isEmpty())
            parts.add(coloured(Component.translatable("gui.twinstar.plan.stock", shortText(row.stock(), row.key())), TEXT));
        if (row.infinite())
            parts.add(coloured(Component.translatable("gui.twinstar.plan.infinite"), GOOD));
        if (!row.missing().isEmpty())
            parts.add(coloured(Component.translatable("gui.twinstar.plan.missing", shortText(row.missing(), row.key())), BAD));
        if (row.missingMachine() != null)
            parts.add(coloured(Component.translatable("gui.twinstar.plan.missing_machine",
                    AEKeyRendering.getDisplayName(row.missingMachine())), BAD));
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                out.append(coloured(Component.literal("・"), DIM));
            out.append(parts.get(i));
        }
        return out;
    }

    public static List<Component> rowTooltip(PlanView.Row row) {
        List<Component> lines = new ArrayList<>();
        lines.add(AEKeyRendering.getDisplayName(row.key()));
        lines.add(Component.translatable("gui.twinstar.plan.uses", fullText(row.amount(), row.key())));
        if (!row.runs().isEmpty())
            lines.add(coloured(Component.translatable("gui.twinstar.plan.runs", fullText(row.runs(), null)), CRAFT));
        if (!row.stock().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.stock", fullText(row.stock(), row.key())));
        if (row.infinite())
            lines.add(coloured(Component.translatable("gui.twinstar.plan.infinite"), GOOD));
        if (!row.missing().isEmpty())
            lines.add(coloured(Component.translatable("gui.twinstar.plan.missing", fullText(row.missing(), row.key())), BAD));
        if (row.missingMachine() != null)
            lines.add(coloured(Component.translatable("gui.twinstar.plan.missing_machine",
                    AEKeyRendering.getDisplayName(row.missingMachine())), BAD));
        if (row.shared())
            lines.add(coloured(Component.translatable("gui.twinstar.plan.shared.tooltip"), DIM));
        return lines;
    }

    // ── amounts (sent as digits when they fit a long, else already in unit form) ──

    private static @Nullable BigInteger digits(String s) {
        if (s.isEmpty() || s.length() > 19)
            return null;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i)))
                return null;
        return new BigInteger(s);
    }

    public static String shortText(String s, @Nullable AEKey key) {
        BigInteger v = digits(s);
        if (v == null)
            return s;
        if (key != null && key.getAmountPerUnit() > 1)
            return BigAmountFormat.shortForm(v, key.getAmountPerUnit(), unit(key));
        return BigAmountFormat.unitForm(v);
    }

    public static String fullText(String s, @Nullable AEKey key) {
        BigInteger v = digits(s);
        if (v == null)
            return s;
        if (key != null && key.getAmountPerUnit() > 1)
            return BigAmountFormat.fullForm(v, key.getAmountPerUnit(), unit(key));
        return NumberFormat.getInstance().format(v);
    }

    private static String unit(AEKey key) {
        String u = key.getUnitSymbol();
        return u != null ? u : "";
    }

    public static String limit(String s) {
        return s.isEmpty() ? "∞" : shortText(s, null);
    }

    public static String millis(long nanos) {
        double ms = nanos / 1e6;
        return ms < 10 ? String.format("%.1f", ms) : String.valueOf(Math.round(ms));
    }

    public static Component errorText(String code) {
        return Component.translatableWithFallback("gui.twinstar.plan.error." + code.toLowerCase(Locale.ROOT), code);
    }

    /**
     * An amount (base units, decimal text) as the amount field shows it: plain for a readable number, else
     * as mantissa and exponent ({@code 1e10000}), which the field reads back exactly.
     */
    public static String fieldText(String baseUnits, int perUnit) {
        try {
            return displayText(new BigDecimal(new BigInteger(baseUnits)).divide(BigDecimal.valueOf(Math.max(1, perUnit))));
        } catch (ArithmeticException | NumberFormatException e) {
            return "1";
        }
    }

    public static String displayText(BigDecimal v) {
        v = v.stripTrailingZeros();
        int exponent = -v.scale();
        return exponent >= 6 && v.precision() <= 20 ? v.unscaledValue() + "e" + exponent : v.toPlainString();
    }
}
