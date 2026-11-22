package com.lumina.twinstar.client.hover;

import appeng.api.stacks.AEKey;

/** The material drawn under the mouse and the screen square it is drawn in. */
public record KeyHit(AEKey key, int x, int y, int w, int h) {
}
