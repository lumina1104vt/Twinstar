package com.lumina.twinstar.algorithm.cache;

import org.jetbrains.annotations.Nullable;

/** LU with partial pivoting for the small dense systems of policy cycles (cost and BOM estimates). */
final class DenseLu {
    private static final double SINGULAR = 1e-12;

    private final double[][] lu;
    private final int[] perm;

    private DenseLu(double[][] lu, int[] perm) {
        this.lu = lu;
        this.perm = perm;
    }

    /** null when the matrix is (numerically) singular or not finite. */
    static @Nullable DenseLu factor(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][];
        double scale = 0;
        for (int i = 0; i < n; i++) {
            a[i] = matrix[i].clone();
            for (double v : a[i]) {
                if (!Double.isFinite(v))
                    return null;
                scale = Math.max(scale, Math.abs(v));
            }
        }
        if (scale == 0)
            return null;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++)
            perm[i] = i;
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++)
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c]))
                    piv = r;
            if (Math.abs(a[piv][c]) <= SINGULAR * scale)
                return null;
            if (piv != c) {
                double[] t = a[piv];
                a[piv] = a[c];
                a[c] = t;
                int tp = perm[piv];
                perm[piv] = perm[c];
                perm[c] = tp;
            }
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                a[r][c] = f;
                if (f != 0)
                    for (int k = c + 1; k < n; k++)
                        a[r][k] -= f * a[c][k];
            }
        }
        return new DenseLu(a, perm);
    }

    double[] solve(double[] b) {
        int n = lu.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++)
            x[i] = b[perm[i]];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < i; k++)
                x[i] -= lu[i][k] * x[k];
        for (int i = n - 1; i >= 0; i--) {
            for (int k = i + 1; k < n; k++)
                x[i] -= lu[i][k] * x[k];
            x[i] /= lu[i][i];
        }
        return x;
    }
}
