package com.lumina.twinstar.usage;

/** An AE2 inventory that knows whose it is (an ME interface's storage), added by a mixin so the monitor can name the sender. */
public interface OwnedInventory {
    void twinstar$setOwner(Object owner);

    Object twinstar$getOwner();
}
