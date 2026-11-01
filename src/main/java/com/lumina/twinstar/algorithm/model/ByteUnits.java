package com.lumina.twinstar.algorithm.model;

/**
 * Black-hole capacity counted like AE2 counts a crafting CPU's bytes: an amount of a key takes
 * amount × 8 ÷ its type's amount per byte (AE2's {@code AEKeyType.getAmountPerByte}: 8 for items,
 * so one byte per item; a fluid's makes one byte per bucket). Ids are the engine's item ids.
 */
@FunctionalInterface
public interface ByteUnits {
    /** Items only (tests, and keys whose type is unknown). */
    ByteUnits ITEMS = item -> 8;

    /** AE2's amount per byte of the item's key type; 8 for items. */
    int amountPerByte(int item);

    /** Bytes {@code amount} of {@code item} takes, rounded up. */
    default Amount bytes(int item, Amount amount) {
        int apb = amountPerByte(item);
        return apb == 8 || apb <= 0 ? amount : amount.mul(8).ceilDiv(apb);
    }
}
