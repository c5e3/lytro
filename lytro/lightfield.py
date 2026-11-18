"""Decode and render first-generation Lytro light-field RAW files.

Pipeline:
  packed 12-bit Bayer -> demosaiced linear RGB -> hexagonal microlens grid ->
  sub-aperture views V[k] (one per pixel offset under each lenslet) ->
  shift-and-add refocusing + depth estimation from a focal sweep.
"""
from __future__ import annotations

import os

import numpy as np
import cv2

SQRT3_2 = np.sqrt(3.0) / 2.0

# OpenCV names Bayer patterns after the 2nd row / 2nd column of the tile,
# so the code for a sensor whose upper-left pixel is X is "shifted" by one.
_BAYER_CODES = {
    "b": cv2.COLOR_BayerRG2RGB,   # B G / G R  (BGGR)
    "r": cv2.COLOR_BayerBG2RGB,   # R G / G B  (RGGB)
    "gr": cv2.COLOR_BayerGB2RGB,  # G R / B G  (GRBG)
    "gb": cv2.COLOR_BayerGR2RGB,  # G B / R G  (GBRG)
}


def unpack12(data: bytes, width: int, height: int) -> np.ndarray:
    """Unpack big-endian 12-bit packed pixels (2 pixels per 3 bytes)."""
    b = np.frombuffer(data, np.uint8, count=width * height * 3 // 2).reshape(-1, 3).astype(np.uint16)
    p0 = (b[:, 0] << 4) | (b[:, 1] >> 4)
    p1 = ((b[:, 1] & 0x0F) << 8) | b[:, 2]
    return np.stack([p0, p1], 1).reshape(height, width)


def frame_metadata(meta: dict) -> dict:
    return meta["master"]["picture"]["frameArray"][0]["frame"]["metadata"]


class LightField:
    # Sub-aperture sampling radius inside each lenslet image (pixels).
    RADIUS = 4.0
    # Bump whenever decoding or depth estimation changes, so stale disk
    # caches are ignored instead of shown.
    CACHE_VERSION = 1

    def __init__(self, raw: bytes | None, meta: dict):
        self.meta = meta
        fm = frame_metadata(meta)
        img, dev = fm["image"], fm["devices"]
        self.W, self.H = img["width"], img["height"]
        details = img["rawDetails"]
        self.black = details["pixelFormat"]["black"]
        self.white = details["pixelFormat"]["white"]
        self.upper_left = details["mosaic"]["upperLeftPixel"]
        col = img["color"]
        self.wb = col["whiteBalanceGain"]
        self.ccm = np.array(col["ccmRgbToSrgbArray"], np.float32).reshape(3, 3)
        self.gamma = float(col.get("gamma", 0.4167))
        self.exposure = 2.0 ** (-float(img.get("modulationExposureBias", 0.0)))

        mla, sensor = dev["mla"], dev["sensor"]
        pix = sensor["pixelPitch"]
        self.pitch = mla["lensPitch"] / pix
        self.rot = mla["rotation"]
        self.scale_x = mla["scaleFactor"]["x"]
        self.scale_y = mla["scaleFactor"]["y"]
        self.off_x = mla["sensorOffset"]["x"] / pix
        self.off_y = mla["sensorOffset"]["y"] / pix

        self.depth: np.ndarray | None = None
        self.depth_conf: np.ndarray | None = None
        if raw is not None:
            self._decode(raw)

    # ------------------------------------------------------------ disk cache
    def save(self, path) -> None:
        """Store the decoded views (and the depth map, if computed) so the
        picture can be reopened without decoding the RAW again."""
        data = dict(version=self.CACHE_VERSION, views=self.views.astype(np.float16),
                    offsets=self.offsets, saturated=self.saturated,
                    grid_offset=np.array([self.off_x, self.off_y]))
        if self.depth is not None:
            data.update(depth=self.depth, depth_conf=self.depth_conf)
        tmp = str(path) + ".part.npz"
        np.savez(tmp, **data)
        os.replace(tmp, path)

    @classmethod
    def load(cls, path, meta: dict) -> "LightField | None":
        """Restore a light field saved with save(); None if the file is
        missing, unreadable or from an older decoder version."""
        try:
            with np.load(path) as z:
                if int(z["version"]) != cls.CACHE_VERSION:
                    return None
                lf = cls(None, meta)
                lf.off_x, lf.off_y = (float(v) for v in z["grid_offset"])
                lf._set_views(z["views"].astype(np.float32), z["offsets"], z["saturated"])
                if "depth" in z:
                    lf.depth, lf.depth_conf = z["depth"], z["depth_conf"]
                return lf
        except (OSError, KeyError, ValueError):
            return None

    # ------------------------------------------------------------------ decode
    def _decode(self, raw: bytes):
        bayer = unpack12(raw, self.W, self.H)
        rgb = cv2.cvtColor(bayer, _BAYER_CODES[self.upper_left]).astype(np.float32)
        blk = np.array([self.black["r"], (self.black["gr"] + self.black["gb"]) / 2, self.black["b"]], np.float32)
        wht = np.array([self.white["r"], (self.white["gr"] + self.white["gb"]) / 2, self.white["b"]], np.float32)
        gains = np.array([self.wb["r"], (self.wb["gr"] + self.wb["gb"]) / 2, self.wb["b"]], np.float32)
        rgb = (rgb - blk) / (wht - blk) * gains
        np.clip(rgb, 0, None, out=rgb)
        lum = cv2.GaussianBlur(rgb.mean(2), (0, 0), 1.0)
        self._refine_grid(lum)
        self._extract_views(rgb)

    def _grid(self, ox: float, oy: float):
        """Lenslet centres (X, Y) on the sensor, indexed [row, col]."""
        p = self.pitch
        nr = int(self.H / (p * SQRT3_2) / 2) + 2
        nc = int(self.W / p / 2) + 2
        jj, ii = np.meshgrid(np.arange(-nr, nr + 1), np.arange(-nc, nc + 1), indexing="ij")
        x = (ii + (jj % 2) * 0.5) * p * self.scale_x
        y = jj * p * SQRT3_2 * self.scale_y
        c, s = np.cos(self.rot), np.sin(self.rot)
        X = c * x - s * y + (self.W - 1) / 2 + ox
        Y = s * x + c * y + (self.H - 1) / 2 + oy
        return X, Y, jj

    def _refine_grid(self, lum: np.ndarray):
        """Small search around the metadata offset: lenslet centres are the
        brightest point of each lenslet image (vignetting), so maximise the
        mean brightness sampled at predicted centres in a bright region."""
        # reference region: the brightest 600x600 tile without clipped
        # highlights (saturated lenslets are flat and carry no signal)
        t = 600
        nty, ntx = self.H // t, self.W // t
        tiles = lum[:nty * t, :ntx * t].reshape(nty, t, ntx, t)
        mean = tiles.mean((1, 3))
        clipped = (tiles > 0.9).mean((1, 3))
        mean[clipped > 0.002] = -1
        ty, tx = np.unravel_index(np.argmax(mean), mean.shape)
        x0, y0 = tx * t + 20, ty * t + 20
        region = (x0, y0, x0 + t - 40, y0 + t - 40)

        def score(dx, dy):
            X, Y, _ = self._grid(self.off_x + dx, self.off_y + dy)
            m = (X > region[0]) & (X < region[2]) & (Y > region[1]) & (Y < region[3])
            return _bilinear(lum, X[m], Y[m]).mean()

        best = max((score(dx, dy), dx, dy)
                   for dx in np.arange(-1.5, 1.51, 0.25) for dy in np.arange(-1.5, 1.51, 0.25))
        self.off_x += best[1]
        self.off_y += best[2]

    def _extract_views(self, rgb: np.ndarray):
        X, Y, jj = self._grid(self.off_x, self.off_y)
        # keep lenslet rows/cols fully inside the sensor (with margin)
        m = self.RADIUS + 2
        inside = (X > m) & (X < self.W - m - 1) & (Y > m) & (Y < self.H - m - 1)
        # largest rectangle of lenslets fully on the sensor: start from the
        # rows/cols through the centre, then shrink until everything is inside
        cr, cc = np.array(inside.shape) // 2
        rows = np.where(inside[:, cc])[0]
        cols = np.where(inside[cr, :])[0]
        while not inside[np.ix_(rows, cols)].all():
            rows, cols = rows[1:-1], cols[1:-1]
        X, Y, jj = X[np.ix_(rows, cols)], Y[np.ix_(rows, cols)], jj[np.ix_(rows, cols)]
        odd = (jj[:, 0] % 2) == 1

        r = int(np.floor(self.RADIUS))
        offs = [(u, v) for v in range(-r, r + 1) for u in range(-r, r + 1) if u * u + v * v <= self.RADIUS ** 2]
        c, s = np.cos(self.rot), np.sin(self.rot)
        views = []
        for u, v in offs:
            du, dv = c * u - s * v, s * u + c * v
            view = _bilinear(rgb, X + du, Y + dv)  # (rows, cols, 3)
            views.append(_hex_to_rect(view, odd))
        V = np.stack(views).astype(np.float32)  # (K, h, w, 3)

        # crude devignetting: equalise each view's brightness to the central view
        means = V.reshape(len(offs), -1).mean(1)
        centre = offs.index((0, 0))
        V *= (means[centre] / np.maximum(means, 1e-6))[:, None, None, None]

        # pixels that were clipped on the sensor (in any view)
        saturated = cv2.dilate((V.max(3).max(0) > 0.95).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0

        # colour: camera RGB -> sRGB primaries, plus exposure bias (still linear)
        V = (V.reshape(-1, 3) @ self.ccm.T).reshape(V.shape) * self.exposure
        np.clip(V, 0, None, out=V)
        self._set_views(V, np.array(offs, np.float32), saturated)

    def _set_views(self, V: np.ndarray, offsets: np.ndarray, saturated: np.ndarray):
        self.views = V
        self.offsets = offsets  # (K, 2) in sensor pixels (u, v)
        self.radii = np.hypot(self.offsets[:, 0], self.offsets[:, 1])
        self.saturated = saturated
        self.h, self.w = V.shape[1:3]

        # Views are shifted in the Fourier domain: exact sub-pixel shifts with
        # no interpolation blur (bilinear blur would bias the depth sweep
        # towards zero shift). Pad with reflection so wrap-around stays out.
        P = self.PAD
        Vp = np.pad(V, ((0, 0), (P, P), (P, P), (0, 0)), mode="reflect")
        self.Hp, self.Wp = Vp.shape[1:3]
        self.F = np.fft.rfft2(Vp, axes=(1, 2)).astype(np.complex64)          # (K, Hp, Wf, 3)
        self.Fg = np.fft.rfft2(Vp.mean(3), axes=(1, 2)).astype(np.complex64)  # (K, Hp, Wf)
        self.fy = np.fft.fftfreq(self.Hp).astype(np.float32)[:, None]
        self.fx = np.fft.rfftfreq(self.Wp).astype(np.float32)[None, :]

    # ------------------------------------------------------------------ render
    SLOPE_RANGE = (-1.6, 1.6)
    PAD = 16

    def _phase_xy(self, slope: float, sel: np.ndarray):
        """Separable Fourier phase ramps that shift view k by slope*(u_k, v_k):
        ey (k, Hp, 1) * ex (k, 1, Wf). Views are on a grid of one output
        pixel per lenslet and y was rescaled by sqrt(3)/2 in _hex_to_rect,
        so shifts are isotropic."""
        u = self.offsets[sel, 0][:, None, None]
        v = self.offsets[sel, 1][:, None, None]
        ex = np.exp((-2j * np.pi * slope) * u * self.fx).astype(np.complex64)
        ey = np.exp((-2j * np.pi * slope) * v * self.fy).astype(np.complex64)
        return ey, ex

    def refocus(self, slope: float, aperture: float) -> np.ndarray:
        """Shift-and-add refocus. slope: output-pixel shift per sensor pixel
        of offset under the lenslet; aperture: radius of the synthetic
        aperture in lenslet pixels (0 = pinhole, everything sharp)."""
        sel = np.where(self.radii <= max(aperture, 0.0) + 1e-6)[0]
        ey, ex = self._phase_xy(slope, sel)
        S = np.zeros(self.F.shape[1:], np.complex64)
        for n, k in enumerate(sel):
            S += self.F[k] * (ey[n] * ex[n])[..., None]
        img = np.fft.irfft2(S, s=(self.Hp, self.Wp), axes=(0, 1)) / len(sel)
        P = self.PAD
        return np.clip(img[P:P + self.h, P:P + self.w], 0, None).astype(np.float32)

    def _sweep(self, slopes: np.ndarray, progress=None):
        """Focus scores for each slope: defocus cue (local contrast of the
        refocused image) + correspondence cue (views agree when in focus)."""
        sel = np.where(self.radii <= self.RADIUS)[0]
        P = self.PAD
        defocus = np.empty((len(slopes), self.h, self.w), np.float32)
        corresp = np.empty((len(slopes), self.h, self.w), np.float32)
        for n, sl in enumerate(slopes):
            ey, ex = self._phase_xy(float(sl), sel)
            g = np.fft.irfft2(self.Fg[sel] * ey * ex, s=(self.Hp, self.Wp), axes=(1, 2))
            g = g[:, P:P + self.h, P:P + self.w].astype(np.float32)
            mean = np.clip(g.mean(0), 0, None)
            var = g.var(0)
            lap = cv2.Laplacian(np.sqrt(mean), cv2.CV_32F, ksize=3)
            defocus[n] = cv2.GaussianBlur(lap * lap, (0, 0), 1.5)
            corresp[n] = cv2.GaussianBlur(var / (mean * mean + 1e-4), (0, 0), 1.5)
            if progress:
                progress(n + 1)

        def norm(a):  # per-pixel normalisation to [0,1] over the sweep
            lo, hi = a.min(0), a.max(0)
            return (a - lo) / (hi - lo + 1e-9)

        score = norm(defocus) + (1.0 - norm(corresp))
        idx = score.argmax(0)
        # sub-step refinement by parabolic fit around the peak
        i0 = np.clip(idx, 1, len(slopes) - 2)
        yy, xx = np.indices(idx.shape)
        a, b, c = score[i0 - 1, yy, xx], score[i0, yy, xx], score[i0 + 1, yy, xx]
        den = a - 2 * b + c
        frac = np.where(np.abs(den) > 1e-6, 0.5 * (a - c) / den, 0).clip(-0.5, 0.5)
        depth = slopes[i0] + frac * (slopes[1] - slopes[0])
        # confidence: how pronounced the peak is, and whether there is any
        # texture at all (flat regions can't be measured)
        peak = score.max(0) - np.median(score, 0)
        texture = defocus.max(0)
        conf = peak * np.sqrt(texture / (np.percentile(texture, 90) + 1e-12))
        conf[self.saturated] = 0  # clipped highlights have no usable detail
        return depth.astype(np.float32), conf.clip(0, None).astype(np.float32)

    def compute_depth(self, coarse: int = 17, fine: int = 25, progress=None):
        """Per-pixel depth (as refocus slope) in two passes: a coarse sweep
        over the whole range, then a fine sweep over the range the scene
        actually occupies. progress(fraction) is called as it goes."""
        total = coarse + fine
        cb = (lambda n: progress(n / total)) if progress else None
        s0 = np.linspace(*self.SLOPE_RANGE, coarse).astype(np.float32)
        d0, c0 = self._sweep(s0, cb)
        good = c0 > np.percentile(c0, 70)
        lo, hi = (np.percentile(d0[good], [3, 97]) if good.any() else self.SLOPE_RANGE)
        margin = 2 * (s0[1] - s0[0])
        lo, hi = max(lo - margin, self.SLOPE_RANGE[0]), min(hi + margin, self.SLOPE_RANGE[1])
        cb2 = (lambda n: progress((coarse + n) / total)) if progress else None
        depth, conf = self._sweep(np.linspace(lo, hi, fine).astype(np.float32), cb2)
        depth = cv2.medianBlur(depth, 5)

        # fill unreliable areas from confident neighbours: normalised
        # convolution at a local and a global scale, blended by confidence
        c = (conf / (np.percentile(conf, 90) + 1e-9)).clip(0, 1) ** 2

        def nconv(sigma):
            num = cv2.GaussianBlur(depth * c, (0, 0), sigma)
            den = cv2.GaussianBlur(c, (0, 0), sigma)
            return num / (den + 1e-9), den

        d_local, w_local = nconv(4)
        d_global, _ = nconv(30)
        w_local = (w_local / (np.percentile(w_local, 90) + 1e-9)).clip(0, 1)
        background = w_local * d_local + (1 - w_local) * d_global
        depth = (c * depth + (1 - c) * background).astype(np.float32)
        # edge-aware smoothing guided by the all-in-focus image
        guide = np.sqrt(self.refocus(0.0, 0.0).mean(2))
        guide = guide / (guide.max() + 1e-9)
        self.depth = _guided_filter(guide, depth, 6, 1e-3)
        self.depth_conf = conf
        return self.depth, conf

    def focus_slope_at(self, x: float, y: float, win: int = 4) -> float:
        """Refocus slope for a point in view coordinates (from depth map)."""
        if self.depth is None:
            raise RuntimeError("depth not computed")
        xi, yi = int(round(x)), int(round(y))
        y0, y1 = max(yi - win, 0), min(yi + win + 1, self.h)
        x0, x1 = max(xi - win, 0), min(xi + win + 1, self.w)
        d = self.depth[y0:y1, x0:x1].ravel()
        w = self.depth_conf[y0:y1, x0:x1].ravel() + 1e-6
        order = np.argsort(d)  # confidence-weighted median
        cw = np.cumsum(w[order])
        return float(d[order][np.searchsorted(cw, cw[-1] / 2)])

    # ----------------------------------------------------------------- output
    def to_srgb8(self, lin: np.ndarray) -> np.ndarray:
        out = np.clip(lin, 0, 1) ** self.gamma
        return (out * 255 + 0.5).astype(np.uint8)

    def depth_rgb8(self) -> np.ndarray:
        d = self.depth
        lo, hi = np.percentile(d, [2, 98])
        n = ((d - lo) / (hi - lo + 1e-9)).clip(0, 1)
        g = (n * 255).astype(np.uint8)
        return cv2.cvtColor(cv2.applyColorMap(g, cv2.COLORMAP_TURBO), cv2.COLOR_BGR2RGB)


def _bilinear(img: np.ndarray, X: np.ndarray, Y: np.ndarray) -> np.ndarray:
    x0 = np.floor(X).astype(np.int32)
    y0 = np.floor(Y).astype(np.int32)
    fx = (X - x0).astype(np.float32)
    fy = (Y - y0).astype(np.float32)
    if img.ndim == 3:
        fx, fy = fx[..., None], fy[..., None]
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy)
            + img[y0 + 1, x0] * (1 - fx) * fy + img[y0 + 1, x0 + 1] * fx * fy)


def _guided_filter(I: np.ndarray, p: np.ndarray, r: int, eps: float) -> np.ndarray:
    """He et al. guided filter (grey guide), edge-preserving smoothing of p."""
    k = (2 * r + 1, 2 * r + 1)
    mean_I = cv2.blur(I, k)
    mean_p = cv2.blur(p, k)
    cov_Ip = cv2.blur(I * p, k) - mean_I * mean_p
    var_I = cv2.blur(I * I, k) - mean_I * mean_I
    a = cov_Ip / (var_I + eps)
    b = mean_p - a * mean_I
    return (cv2.blur(a, k) * I + cv2.blur(b, k)).astype(np.float32)


def _hex_to_rect(view: np.ndarray, odd: np.ndarray) -> np.ndarray:
    """Resample a view sampled on the hex lenslet grid (odd rows shifted by
    +0.5 lenslet) onto a square grid with equal spacing in x and y."""
    out = view.copy()
    # odd rows were sampled at x = i + 0.5: interpolate back to x = i
    o = out[odd]
    o[:, 1:] = 0.5 * (view[odd][:, :-1] + view[odd][:, 1:])
    out[odd] = o
    h, w = out.shape[:2]
    return cv2.resize(out, (w, int(round(h * SQRT3_2))), interpolation=cv2.INTER_AREA)
