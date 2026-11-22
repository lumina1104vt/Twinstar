package com.lumina.twinstar.metrics;

import java.lang.management.ManagementFactory;

/** Helpers for the memory tests. */
public final class MemoryEstimateProbe {
    private MemoryEstimateProbe() {
    }

    /** The test JVM uses 4-byte references and 12-byte headers, which is what the estimates stand for. */
    public static boolean compressedReferences() {
        try {
            var bean = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            return Boolean.parseBoolean(bean.getVMOption("UseCompressedOops").getValue())
                    && Boolean.parseBoolean(bean.getVMOption("UseCompressedClassPointers").getValue());
        } catch (Throwable t) {
            return false;
        }
    }
}
