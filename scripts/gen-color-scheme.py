#!/usr/bin/env python3
"""Generate the Material 3 colour roles that `ui/theme/Color.kt` does not define by hand.

Run it, then paste the printed block into `app/src/main/java/app/getknit/knit/ui/theme/Color.kt`:

    python3 scripts/gen-color-scheme.py

**Why this exists.** `lightColorScheme()`/`darkColorScheme()` take ~45 roles. Knit set 24 and let the
rest default, so `surfaceContainer`, `outlineVariant`, `inverseSurface` and friends fell through to
Material's *baseline* palette, which is tinted purple (`surfaceContainer` = #F3EDF7, hue 276 degrees)
and sat next to Knit's warm coral surfaces (hue ~14). Every Card, AlertDialog, DropdownMenu,
ModalBottomSheet and scrolled TopAppBar drew one of those. This fills the gap.

**What it does NOT do: it does not touch the 24 roles that already exist.** That is the whole
constraint. Knit's palette was authored by hand rather than generated, so the families do not all sit
on one tonal ramp -- the coral primary in particular is off its own ramp by up to 31/255 per channel
(`CoralPrimaryLight` is at tone 45, not 40, and carries far more chroma than its own containers).
Re-deriving those would restyle the app, which is not what filling a gap should do.

Two things make that constraint cheap to honour:

  * **Most missing roles are aliases.** Material's own light/dark mappings (verified against
    `DynamicTonalPalette.android.kt`, material3 1.4.0) put every `*Fixed` role, `inversePrimary` and
    `surfaceTint` on tones Knit already defines -- `primaryFixed` is primary tone 90, which is exactly
    `CoralPrimaryContainerLight`. Those 14 roles are re-used verbatim, so the incoherent primary family
    never has to be regenerated.
  * **Everything genuinely new comes from one family.** Material derives every neutral surface from the
    *neutralVariant* palette, and that is the family Knit authored most consistently: a single ramp at
    hue 41.3 / chroma 8.7 reproduces all five of its existing members to within 3/255 per channel. The
    11 values printed below are tones of that ramp, all near-neutral, where a residual that small is not
    visible.

`verify()` re-derives every existing constant from the fitted ramp and fails if any drifts further than
MAX_DRIFT, so a future edit to `Color.kt` that breaks the fit is caught here rather than by eye.

The HCT (hue / chroma / tone) maths is a port of Material's material-color-utilities; `self_test()`
checks it against Material's own published baseline palette.
"""

import math
import sys

# --- The fitted neutralVariant ramp -----------------------------------------
# Fitted to Knit's five existing neutralVariant members (SurfaceVariantLight, OnSurfaceVariantLight,
# OutlineLight, OnSurfaceVariantDark, OutlineDark). See verify().
NV_HUE = 41.3
NV_CHROMA = 8.7

# Largest per-channel drift tolerated when re-deriving an existing constant.
MAX_DRIFT = 6

# --- sRGB <-> XYZ (D65) ------------------------------------------------------

SRGB_TO_XYZ = (
    (0.41233895, 0.35762064, 0.18051042),
    (0.2126, 0.7152, 0.0722),
    (0.01932141, 0.11916382, 0.95034478),
)
WHITE_POINT_D65 = (95.047, 100.0, 108.883)


def _linearized(c):
    n = c / 255.0
    return n / 12.92 * 100.0 if n <= 0.040449936 else ((n + 0.055) / 1.055) ** 2.4 * 100.0


def _delinearized(c):
    n = c / 100.0
    d = n * 12.92 if n <= 0.0031308 else 1.055 * (n ** (1.0 / 2.4)) - 0.055
    return max(0, min(255, round(d * 255.0)))


def _lab_f(t):
    e, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    return t ** (1.0 / 3.0) if t > e else (kappa * t + 16.0) / 116.0


def _lab_invf(ft):
    e, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    ft3 = ft ** 3
    return ft3 if ft3 > e else (116.0 * ft - 16.0) / kappa


def y_from_lstar(lstar):
    return 100.0 * _lab_invf((lstar + 16.0) / 116.0)


def lstar_from_y(y):
    return _lab_f(y / 100.0) * 116.0 - 16.0


def argb_from_rgb(r, g, b):
    return (255 << 24) | (r << 16) | (g << 8) | b


def rgb_from_argb(argb):
    return ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)


def argb_from_hex(s):
    s = s.lstrip("#")
    return argb_from_rgb(int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))


def hex_of(argb):
    return "%06X" % (argb & 0xFFFFFF)


def xyz_from_argb(argb):
    lin = [_linearized(c) for c in rgb_from_argb(argb)]
    return tuple(sum(m[i] * lin[i] for i in range(3)) for m in SRGB_TO_XYZ)


def argb_from_lstar(lstar):
    c = _delinearized(y_from_lstar(lstar))
    return argb_from_rgb(c, c, c)


# --- CAM16 -------------------------------------------------------------------


def _lerp(a, b, t):
    return (1.0 - t) * a + t * b


def _signum(x):
    return 0.0 if x == 0 else (1.0 if x > 0 else -1.0)


class ViewingConditions:
    """Material's default viewing conditions; every published M3 palette is computed under these."""

    def __init__(self, adapting_luminance, background_lstar, surround, discounting):
        wp = WHITE_POINT_D65
        r_w = wp[0] * 0.401288 + wp[1] * 0.650173 + wp[2] * -0.051461
        g_w = wp[0] * -0.250268 + wp[1] * 1.204414 + wp[2] * 0.045854
        b_w = wp[0] * -0.002079 + wp[1] * 0.048952 + wp[2] * 0.953127
        f = 0.8 + surround / 10.0
        c = _lerp(0.59, 0.69, (f - 0.9) * 10.0) if f >= 0.9 else _lerp(0.525, 0.59, (f - 0.8) * 10.0)
        d = 1.0 if discounting else f * (1.0 - (1.0 / 3.6) * math.exp((-adapting_luminance - 42.0) / 92.0))
        d = max(0.0, min(1.0, d))
        self.rgb_d = tuple(d * (100.0 / w) + 1.0 - d for w in (r_w, g_w, b_w))
        k = 1.0 / (5.0 * adapting_luminance + 1.0)
        k4 = k ** 4
        k4f = 1.0 - k4
        self.fl = k4 * adapting_luminance + 0.1 * k4f * k4f * ((5.0 * adapting_luminance) ** (1.0 / 3.0))
        self.n = y_from_lstar(background_lstar) / wp[1]
        self.z = 1.48 + math.sqrt(self.n)
        self.nbb = 0.725 / (self.n ** 0.2)
        self.ncb = self.nbb
        self.c = c
        self.nc = f
        facs = [((self.fl * self.rgb_d[i] * w) / 100.0) ** 0.42 for i, w in enumerate((r_w, g_w, b_w))]
        rgb_a = [(400.0 * x) / (x + 27.13) for x in facs]
        self.aw = (40.0 * rgb_a[0] + 20.0 * rgb_a[1] + rgb_a[2]) / 20.0 * self.nbb


VC = ViewingConditions((200.0 / math.pi) * y_from_lstar(50.0) / 100.0, 50.0, 2.0, False)


def cam16_from_argb(argb, vc=VC):
    """Returns (hue_degrees, chroma)."""
    x, y, z = xyz_from_argb(argb)
    r_c = 0.401288 * x + 0.650173 * y - 0.051461 * z
    g_c = -0.250268 * x + 1.204414 * y + 0.045854 * z
    b_c = -0.002079 * x + 0.048952 * y + 0.953127 * z
    a_s = []
    for comp, d in zip((r_c, g_c, b_c), vc.rgb_d):
        v = d * comp
        af = ((vc.fl * abs(v)) / 100.0) ** 0.42
        a_s.append(_signum(v) * 400.0 * af / (af + 27.13))
    r_a, g_a, b_a = a_s
    a = (11.0 * r_a - 12.0 * g_a + b_a) / 11.0
    b = (r_a + g_a - 2.0 * b_a) / 9.0
    u = (20.0 * r_a + 20.0 * g_a + 21.0 * b_a) / 20.0
    p2 = (40.0 * r_a + 20.0 * g_a + b_a) / 20.0
    hue = math.degrees(math.atan2(b, a)) % 360.0
    j = 100.0 * ((p2 * vc.nbb / vc.aw) ** (vc.c * vc.z))
    hue_prime = hue + 360.0 if hue < 20.14 else hue
    e_hue = 0.25 * (math.cos(math.radians(hue_prime) + 2.0) + 3.8)
    p1 = 50000.0 / 13.0 * e_hue * vc.nc * vc.ncb
    t = p1 * math.hypot(a, b) / (u + 0.305)
    alpha = (t ** 0.9) * ((1.64 - (0.29 ** vc.n)) ** 0.73)
    return hue, alpha * math.sqrt(j / 100.0)


def _cam16_to_xyz(j, c, h, vc=VC):
    alpha = 0.0 if (c == 0.0 or j == 0.0) else c / math.sqrt(j / 100.0)
    t = (alpha / ((1.64 - (0.29 ** vc.n)) ** 0.73)) ** (1.0 / 0.9)
    h_rad = math.radians(h)
    e_hue = 0.25 * (math.cos(h_rad + 2.0) + 3.8)
    ac = vc.aw * ((j / 100.0) ** (1.0 / vc.c / vc.z))
    p1 = e_hue * (50000.0 / 13.0) * vc.nc * vc.ncb
    p2 = ac / vc.nbb
    h_sin, h_cos = math.sin(h_rad), math.cos(h_rad)
    gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * h_cos + 108.0 * t * h_sin)
    a, b = gamma * h_cos, gamma * h_sin
    comps = (
        (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0,
        (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0,
        (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0,
    )
    f = []
    for i, ca in enumerate(comps):
        base = max(0.0, (27.13 * abs(ca)) / (400.0 - abs(ca)))
        f.append(_signum(ca) * (100.0 / vc.fl) * (base ** (1.0 / 0.42)) / vc.rgb_d[i])
    r_f, g_f, b_f = f
    return (
        1.86206786 * r_f - 1.01125463 * g_f + 0.14918677 * b_f,
        0.38752654 * r_f + 0.62144744 * g_f - 0.00897398 * b_f,
        -0.01584150 * r_f - 0.03412294 * g_f + 1.04996444 * b_f,
    )


def _linear_rgb(x, y, z):
    return (
        3.2413774792388685 * x - 1.5376652402851851 * y - 0.49885366846268053 * z,
        -0.9691452513005321 * x + 1.8758853451067872 * y + 0.04156585616912061 * z,
        0.05562093689691305 * x - 0.20395524564742123 * y + 1.0571799111220335 * z,
    )


def _find_by_j(hue, chroma, tone):
    """Binary-search lightness J so the CAM16 colour's Y matches `tone`. None if out of sRGB gamut."""
    low, high = 0.0, 100.0
    target_y = y_from_lstar(tone)
    xyz = None
    for _ in range(40):
        mid = (low + high) / 2.0
        xyz = _cam16_to_xyz(mid, chroma, hue)
        if xyz[1] < target_y:
            low = mid
        else:
            high = mid
    if xyz is None or abs(lstar_from_y(xyz[1]) - tone) > 0.05:
        return None
    lin = _linear_rgb(*xyz)
    if not all(-0.3 <= v <= 100.3 for v in lin):
        return None
    return argb_from_rgb(*[_delinearized(max(0.0, min(100.0, v))) for v in lin])


def hct_to_argb(hue, chroma, tone):
    """Gamut-map (hue, chroma, tone) onto the closest displayable sRGB colour."""
    if chroma < 1.0 or round(tone) <= 0 or round(tone) >= 100:
        return argb_from_lstar(tone)
    hue %= 360.0
    low, high, mid = 0.0, chroma, chroma
    answer, first = None, True
    while abs(low - high) >= 0.4:
        candidate = _find_by_j(hue, mid, tone)
        if first:
            if candidate is not None:
                return candidate
            first = False
        elif candidate is None:
            high = mid
        else:
            answer, low = candidate, mid
        mid = low + (high - low) / 2.0
    return answer if answer is not None else argb_from_lstar(tone)


# --- The roles ---------------------------------------------------------------

# Existing constants, keyed by the neutralVariant tone Material assigns them. verify() re-derives each.
NV_ANCHORS = [
    ("SurfaceVariantLight", "F5DDD6", 90),
    ("OnSurfaceVariantLight", "53433D", 30),
    ("OutlineLight", "857369", 50),
    ("OnSurfaceVariantDark", "D8C2BA", 80),
    ("OutlineDark", "A08D85", 60),
    ("BackgroundLight", "FFF8F6", 98),
    ("BackgroundDark", "1A110E", 6),
]

# The neutralVariant tones Color.kt does not already carry. Named by tone rather than by role because
# several roles share one tone (light inverseSurface and dark inverseOnSurface are both tone 20), and
# because Theme.kt mapping role -> tone is then auditable against Material's own assignment. Tones read
# out of dynamicLightColorScheme31 / dynamicDarkColorScheme31, material3 1.4.0.
GENERATED = [
    ("NeutralVariant4", 4),
    ("NeutralVariant12", 12),
    ("NeutralVariant17", 17),
    ("NeutralVariant20", 20),
    ("NeutralVariant22", 22),
    ("NeutralVariant24", 24),
    ("NeutralVariant87", 87),
    ("NeutralVariant92", 92),
    ("NeutralVariant94", 94),
    ("NeutralVariant95", 95),
    ("NeutralVariant96", 96),
]


def verify():
    """Re-derive every existing constant from the fitted ramp. Returns the worst per-channel drift."""
    worst = 0
    print("# Re-deriving Knit's existing neutralVariant roles from hue %.1f / chroma %.1f:" % (NV_HUE, NV_CHROMA))
    for name, want, tone in NV_ANCHORS:
        got = hex_of(hct_to_argb(NV_HUE, NV_CHROMA, float(tone)))
        drift = max(abs(a - b) for a, b in zip(rgb_from_argb(argb_from_hex(want)), rgb_from_argb(argb_from_hex(got))))
        worst = max(worst, drift)
        flag = "  " if drift <= MAX_DRIFT else "!!"
        print("#   %s %-26s tone %-3d  have #%s  ramp #%s  drift %d" % (flag, name, tone, want, got, drift))
    print("# Worst drift: %d (tolerance %d)" % (worst, MAX_DRIFT))
    return worst


def self_test():
    """Check the HCT port against Material's own published baseline palette (seed #6750A4)."""
    hue, chroma = cam16_from_argb(argb_from_hex("6750A4"))
    got = hex_of(hct_to_argb(hue, chroma, 40.0))
    assert got == "6750A4", "HCT round-trip broken: tone 40 of the M3 seed gave #%s" % got
    # Material's own NeutralVariant80 is #CAC4D0; ours must land within a couple of steps of it.
    base_hue, _ = cam16_from_argb(argb_from_hex("6750A4"))
    nv80 = hex_of(hct_to_argb(base_hue, 8.0, 80.0))
    drift = max(abs(a - b) for a, b in zip(rgb_from_argb(argb_from_hex("CAC4D0")), rgb_from_argb(argb_from_hex(nv80))))
    assert drift <= 2, "HCT port drifts %d from Material's NeutralVariant80 (got #%s)" % (drift, nv80)


def main():
    self_test()
    worst = verify()
    if worst > MAX_DRIFT:
        print("\nERROR: the fitted ramp no longer reproduces Color.kt. Re-fit NV_HUE/NV_CHROMA.", file=sys.stderr)
        return 1
    print("\n// Generated by scripts/gen-color-scheme.py -- neutralVariant tones at hue %.1f / chroma %.1f." % (NV_HUE, NV_CHROMA))
    print("// Tones are Material's own role mapping (material3 1.4.0 dynamicLight/DarkColorScheme31).")
    for name, tone in GENERATED:
        print("val %s = Color(0xFF%s)" % (name, hex_of(hct_to_argb(NV_HUE, NV_CHROMA, float(tone)))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
