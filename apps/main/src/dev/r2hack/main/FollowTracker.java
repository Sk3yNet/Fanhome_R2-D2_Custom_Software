package dev.r2hack.main;

/**
 * Keeps track of one person in the camera image for "follow me", with no extra libraries.
 *
 * A face tells us who to follow (FaceTracker finds it), but a person walking away shows R2 their back,
 * so the tracker locks onto the colours of their clothes instead: the region under the face. It keeps a
 * colour histogram of that region, weighted against the rest of the picture (colours that are common
 * everywhere count for little), and each frame finds the region again with mean shift on a coarse grid.
 * How wide the region looks, compared with when it was locked, says whether the person moved away
 * (narrower) or came closer (wider). Width is used rather than area because a close person's clothes run
 * off the bottom of the picture.
 *
 * Plain Java with no Android classes, so it runs in the off-robot tests (apps/test.sh).
 * All positions are on an upright grid of cells: x to the right, y down, as a person would see the picture.
 */
final class FollowTracker {
    /** One frame's result. x is -1 (left edge) to 1 (right edge); size is 1 at the distance it was locked at. */
    static final class Result {
        final boolean found;
        final double x, size, quality;
        final long time;

        Result(boolean found, double x, double size, double quality, long time) {
            this.found = found;
            this.x = x;
            this.size = size;
            this.quality = quality;
            this.time = time;
        }

        @Override
        public String toString() {
            return found ? String.format(java.util.Locale.US, "x %.2f, size %.2f, quality %.2f", x, size, quality) : "lost";
        }
    }

    static final int BINS = 256;                 // 4 brightness x 8 x 8 colour levels
    private static final double FOUND_DENSITY = 0.4, REFIND_DENSITY = 0.55;
    private static final int REFIND_FRAMES = 2;  // a lost target has to show up twice in a row
    private static final double MIN_LOCK_DENSITY = 0.25;
    private static final double SEARCH_MARGIN = 1.4;

    private final float[] weight = new float[BINS];
    private float[] bp = new float[0];
    private double[] integral = new double[0];
    private boolean locked;
    private double lockW, lockH, lockDensity, lockSigma;
    private double cx, cy, scale = 1;
    private boolean lost;
    private int refindStreak;
    private String problem = "";

    // ---- the grid ----------------------------------------------------------------------------

    /** Grid width for a sensor frame w x h sampled every step pixels, after turning it by rot (0, 90 or 270). */
    static int gridW(int w, int h, int rot, int step) {
        return rot == 0 ? w / step : h / step;
    }

    static int gridH(int w, int h, int rot, int step) {
        return rot == 0 ? h / step : w / step;
    }

    /** The histogram bin of one pixel. */
    static int bin(int y, int u, int v) {
        return ((y >> 6) << 6) | ((u >> 5) << 3) | (v >> 5);
    }

    /**
     * Samples an NV21 frame onto the upright grid: one pixel every step pixels, turned like FaceTracker does
     * (rot 90 = a quarter turn clockwise, which is how R2's sideways camera becomes upright). out holds one
     * histogram bin per cell, row by row; it is reused if it is the right size.
     */
    static int[] sample(byte[] nv21, int w, int h, int rot, int step, int[] out) {
        int sgw = w / step, sgh = h / step;
        int gw = gridW(w, h, rot, step), gh = gridH(w, h, rot, step);
        if (out == null || out.length != gw * gh) out = new int[gw * gh];
        int half = step / 2, base = w * h;
        for (int v = 0; v < gh; v++) {
            for (int u = 0; u < gw; u++) {
                int sx, sy;
                if (rot == 90) {
                    sx = v;
                    sy = sgh - 1 - u;
                } else if (rot == 270) {
                    sx = sgw - 1 - v;
                    sy = u;
                } else {
                    sx = u;
                    sy = v;
                }
                int px = sx * step + half, py = sy * step + half;
                int yy = nv21[py * w + px] & 0xff;
                int c = base + (py >> 1) * w + (px & ~1);   // NV21: V then U, one pair per 2x2 pixels
                int vv = nv21[c] & 0xff, uu = nv21[c + 1] & 0xff;
                out[v * gw + u] = bin(yy, uu, vv);
            }
        }
        return out;
    }

    // ---- locking ----------------------------------------------------------------------------

    boolean locked() {
        return locked;
    }

    /** Why the last lock failed, for the log. */
    String problem() {
        return problem;
    }

    void reset() {
        locked = false;
        lost = false;
        refindStreak = 0;
    }

    /**
     * Locks onto the clothes under a face. faceX and faceY are the face's middle and faceW its width, all as
     * fractions of the picture's width and height (faceW of its width). Returns false if the region under
     * the face is too small or looks too much like everything else to follow.
     */
    boolean lock(int[] cells, int gw, int gh, double faceX, double faceY, double faceW) {
        double fw = faceW * gw;
        double x0 = faceX * gw - 1.2 * fw, x1 = faceX * gw + 1.2 * fw;
        double y0 = faceY * gh + 0.9 * fw, y1 = faceY * gh + 3.5 * fw;
        int ix0 = clamp((int) Math.round(x0), 0, gw), ix1 = clamp((int) Math.round(x1), 0, gw);
        int iy0 = clamp((int) Math.round(y0), 0, gh), iy1 = clamp((int) Math.round(y1), 0, gh);
        if (ix1 - ix0 < 3 || iy1 - iy0 < 3) {
            problem = "no room under the face to see clothes";
            return false;
        }
        double[] t = new double[BINS], b = new double[BINS];
        int nt = 0, nb = 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int k = cells[y * gw + x];
                if (x >= ix0 && x < ix1 && y >= iy0 && y < iy1) {
                    t[k]++;
                    nt++;
                } else {
                    b[k]++;
                    nb++;
                }
            }
        }
        float[] wt = new float[BINS];
        for (int k = 0; k < BINS; k++) {
            double pt = t[k] / nt, pb = nb == 0 ? 0 : b[k] / nb;
            wt[k] = pt + pb == 0 ? 0 : (float) Math.max(0, (pt - pb) / (pt + pb));
        }
        double mass = 0, sx = 0, sxx = 0;
        for (int y = iy0; y < iy1; y++) {
            for (int x = ix0; x < ix1; x++) {
                double wgt = wt[cells[y * gw + x]];
                mass += wgt;
                sx += wgt * x;
                sxx += wgt * x * x;
            }
        }
        double area = (double) (ix1 - ix0) * (iy1 - iy0);
        double density = mass / area;
        if (density < MIN_LOCK_DENSITY || mass <= 0) {
            // A failed lock leaves an earlier one as it was.
            problem = String.format(java.util.Locale.US, "clothes look too much like the room (%.2f)", density);
            return false;
        }
        System.arraycopy(wt, 0, weight, 0, BINS);
        lockDensity = density;
        lockW = ix1 - ix0;
        lockH = iy1 - iy0;
        cx = (ix0 + ix1) / 2.0;
        cy = (iy0 + iy1) / 2.0;
        scale = 1;
        // Measure the width the same way track() will, so the same picture gives size 1.
        backProject(cells, gw, gh);
        double w = lockW * SEARCH_MARGIN, h = lockH * SEARCH_MARGIN;
        for (int i = 0; i < 6; i++) {
            double[] m = moments(gw, gh, cx, cy, w, h);
            cx = m[1] / m[0];
            cy = m[2] / m[0];
        }
        lockSigma = sigma(moments(gw, gh, cx, cy, w, h));
        locked = true;
        lost = false;
        refindStreak = 0;
        problem = "";
        return true;
    }

    // ---- tracking ---------------------------------------------------------------------------

    /** Finds the locked region in a new frame (same grid size as at lock time). */
    Result track(int[] cells, int gw, int gh, long now) {
        if (!locked) return new Result(false, 0, 0, 0, now);
        backProject(cells, gw, gh);
        if (lost) {
            // Look over the whole picture for a box of the last known size.
            if (!search(gw, gh)) {
                refindStreak = 0;
                return new Result(false, 0, 0, 0, now);
            }
            if (++refindStreak < REFIND_FRAMES) return new Result(false, 0, 0, 0, now);
            lost = false;
        }
        double density = 0;
        for (int round = 0; round < 3; round++) {
            double w = lockW * scale * SEARCH_MARGIN, h = lockH * scale * SEARCH_MARGIN;
            for (int i = 0; i < 6; i++) {
                double[] m = moments(gw, gh, cx, cy, w, h);
                if (m[0] <= 0) break;
                double nx = m[1] / m[0], ny = m[2] / m[0];
                boolean settled = Math.abs(nx - cx) < 0.3 && Math.abs(ny - cy) < 0.3;
                cx = nx;
                cy = ny;
                if (settled) break;
            }
            double[] m = moments(gw, gh, cx, cy, w, h);
            if (m[0] <= 0) break;
            scale = clampD(sigma(m) / lockSigma, 0.25, 4);
            double[] inner = moments(gw, gh, cx, cy, lockW * scale, lockH * scale);
            density = inner[0] / Math.max(1, boxArea(gw, gh, cx, cy, lockW * scale, lockH * scale));
        }
        double quality = density / lockDensity;
        if (quality < FOUND_DENSITY) {
            lost = true;
            refindStreak = 0;
            return new Result(false, 0, 0, quality, now);
        }
        return new Result(true, clampD(cx / gw * 2 - 1, -1, 1), scale, quality, now);
    }

    private static double sigma(double[] m) {
        double mx = m[1] / m[0];
        return Math.sqrt(Math.max(0.25, m[3] / m[0] - mx * mx));
    }

    /** Looks over the whole picture for the locked colours, at a few sizes. */
    private boolean search(int gw, int gh) {
        int iw = gw + 1;
        if (integral.length != iw * (gh + 1)) integral = new double[iw * (gh + 1)];
        for (int y = 0; y < gh; y++) {
            double row = 0;
            for (int x = 0; x < gw; x++) {
                row += bp[y * gw + x];
                integral[(y + 1) * iw + x + 1] = integral[y * iw + x + 1] + row;
            }
        }
        double found = -1;
        for (double s : new double[] {0.5, 0.75, 1, 1.4}) {
            int bw = Math.max(2, Math.min(gw, (int) Math.round(lockW * s)));
            int bh = Math.max(2, Math.min(gh, (int) Math.round(lockH * s)));
            double best = -1;
            int bx = 0, by = 0;
            for (int y = 0; y + bh <= gh; y++) {
                for (int x = 0; x + bw <= gw; x++) {
                    double sum = integral[(y + bh) * iw + x + bw] - integral[y * iw + x + bw]
                            - integral[(y + bh) * iw + x] + integral[y * iw + x];
                    if (sum > best) {
                        best = sum;
                        bx = x;
                        by = y;
                    }
                }
            }
            // The biggest box that is still dense enough wins: it covers more of the person.
            if (best / (bw * bh) >= REFIND_DENSITY * lockDensity && best > found) {
                found = best;
                cx = bx + bw / 2.0;
                cy = by + bh / 2.0;
                scale = s;
            }
        }
        return found > 0;
    }

    private void backProject(int[] cells, int gw, int gh) {
        if (bp.length != gw * gh) bp = new float[gw * gh];
        for (int i = 0; i < gw * gh; i++) bp[i] = weight[cells[i]];
    }

    /** Sum, sum x, sum y and sum x*x of the back-projection in a box (centre cx, cy; cell centres at x + 0.5). */
    private double[] moments(int gw, int gh, double cx, double cy, double w, double h) {
        int x0 = clamp((int) Math.floor(cx - w / 2), 0, gw), x1 = clamp((int) Math.ceil(cx + w / 2), 0, gw);
        int y0 = clamp((int) Math.floor(cy - h / 2), 0, gh), y1 = clamp((int) Math.ceil(cy + h / 2), 0, gh);
        double m = 0, mx = 0, my = 0, mxx = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                double v = bp[y * gw + x];
                if (v == 0) continue;
                double px = x + 0.5, py = y + 0.5;
                m += v;
                mx += v * px;
                my += v * py;
                mxx += v * px * px;
            }
        }
        return new double[] {m, mx, my, mxx};
    }

    private static double boxArea(int gw, int gh, double cx, double cy, double w, double h) {
        int x0 = clamp((int) Math.floor(cx - w / 2), 0, gw), x1 = clamp((int) Math.ceil(cx + w / 2), 0, gw);
        int y0 = clamp((int) Math.floor(cy - h / 2), 0, gh), y1 = clamp((int) Math.ceil(cy + h / 2), 0, gh);
        return (double) (x1 - x0) * (y1 - y0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
