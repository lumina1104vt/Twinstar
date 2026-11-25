package com.lumina.twinstar.usage;

/** Set while a block takes material back into the network: what leaves its storage then is a return, not a sending. Server thread. */
public final class SendGate {
    public static int returning;

    private SendGate() {
    }
}
