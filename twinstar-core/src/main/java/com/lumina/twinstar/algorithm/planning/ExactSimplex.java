package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Rational;
import org.jetbrains.annotations.Nullable;

/**
 * Two-phase simplex in exact fractions with Bland's rule (no cycling). Only for the small systems
 * of policy cycles (D): the macro pattern's run counts and the Petri-net state equation.
 */
public final class ExactSimplex {
    private static final int MAX_PIVOTS = 10_000;

    private ExactSimplex() {
    }

    /** Is there x ≥ 0 with A x ≥ b? */
    public static boolean feasible(Rational[][] a, Rational[] b) {
        int n = a.length == 0 ? 0 : a[0].length;
        Rational[] zero = new Rational[n];
        java.util.Arrays.fill(zero, Rational.ZERO);
        return minimize(zero, a, b) != null;
    }

    /**
     * min c·x subject to A x ≥ b, x ≥ 0. Returns null when infeasible or unbounded (c ≥ 0 in every
     * use here, so unbounded does not happen).
     */
    public static Rational @Nullable [] minimize(Rational[] c, Rational[][] a, Rational[] b) {
        int m = a.length;
        int n = c.length;
        if (m == 0) {
            Rational[] x = new Rational[n];
            java.util.Arrays.fill(x, Rational.ZERO);
            return x;
        }
        // columns: n originals, m surplus, m artificial, then rhs
        int cols = n + 2 * m;
        Rational[][] t = new Rational[m][cols + 1];
        int[] basis = new int[m];
        for (int i = 0; i < m; i++) {
            boolean flip = b[i].signum() < 0;
            for (int j = 0; j < n; j++)
                t[i][j] = flip ? a[i][j].negate() : a[i][j];
            for (int j = 0; j < m; j++) {
                t[i][n + j] = j == i ? (flip ? Rational.ONE : Rational.ONE.negate()) : Rational.ZERO;
                t[i][n + m + j] = j == i ? Rational.ONE : Rational.ZERO;
            }
            t[i][cols] = flip ? b[i].negate() : b[i];
            basis[i] = n + m + i;
        }

        // phase one: minimise the artificials
        Rational[] cost1 = new Rational[cols];
        for (int j = 0; j < cols; j++)
            cost1[j] = j >= n + m ? Rational.ONE : Rational.ZERO;
        if (!run(t, basis, cost1, cols))
            return null;
        Rational value = Rational.ZERO;
        for (int i = 0; i < m; i++)
            value = value.add(cost1[basis[i]].mul(t[i][cols]));
        if (value.signum() > 0)
            return null;
        // drive artificials out of the basis where possible (a row that stays is redundant)
        for (int i = 0; i < m; i++) {
            if (basis[i] < n + m)
                continue;
            for (int j = 0; j < n + m; j++)
                if (!t[i][j].isZero()) {
                    pivot(t, basis, i, j, cols);
                    break;
                }
        }

        // phase two: the real objective; artificial columns may no longer enter
        Rational[] cost2 = new Rational[cols];
        for (int j = 0; j < cols; j++)
            cost2[j] = j < n ? c[j] : Rational.ZERO;
        if (!run(t, basis, cost2, n + m))
            return null;
        Rational[] x = new Rational[n];
        java.util.Arrays.fill(x, Rational.ZERO);
        for (int i = 0; i < m; i++)
            if (basis[i] < n)
                x[basis[i]] = t[i][cols];
        return x;
    }

    /** Pivots to optimality; only columns below {@code enterLimit} may enter. False = unbounded. */
    private static boolean run(Rational[][] t, int[] basis, Rational[] cost, int enterLimit) {
        int m = t.length;
        int cols = t[0].length - 1;
        for (int it = 0; it < MAX_PIVOTS; it++) {
            int enter = -1;
            for (int j = 0; j < enterLimit; j++) {
                if (isBasic(basis, j))
                    continue;
                Rational rc = cost[j];
                for (int i = 0; i < m; i++)
                    if (!t[i][j].isZero())
                        rc = rc.sub(cost[basis[i]].mul(t[i][j]));
                if (rc.signum() < 0) {
                    enter = j;      // Bland: lowest index
                    break;
                }
            }
            if (enter < 0)
                return true;
            int leave = -1;
            Rational bestRatio = null;
            for (int i = 0; i < m; i++) {
                if (t[i][enter].signum() <= 0)
                    continue;
                Rational ratio = t[i][cols].div(t[i][enter]);
                int cmp = bestRatio == null ? -1 : ratio.compareTo(bestRatio);
                if (cmp < 0 || (cmp == 0 && basis[i] < basis[leave])) {
                    leave = i;
                    bestRatio = ratio;
                }
            }
            if (leave < 0)
                return false;
            pivot(t, basis, leave, enter, cols);
        }
        return true;
    }

    private static boolean isBasic(int[] basis, int j) {
        for (int b : basis)
            if (b == j)
                return true;
        return false;
    }

    private static void pivot(Rational[][] t, int[] basis, int row, int col, int cols) {
        Rational p = t[row][col];
        for (int j = 0; j <= cols; j++)
            t[row][j] = t[row][j].div(p);
        for (int i = 0; i < t.length; i++) {
            if (i == row || t[i][col].isZero())
                continue;
            Rational f = t[i][col];
            for (int j = 0; j <= cols; j++)
                if (!t[row][j].isZero())
                    t[i][j] = t[i][j].sub(f.mul(t[row][j]));
        }
        basis[row] = col;
    }
}
