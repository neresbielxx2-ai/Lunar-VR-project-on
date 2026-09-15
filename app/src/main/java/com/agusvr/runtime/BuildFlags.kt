package com.agusvr.runtime

/**
 * Build-level feature gates (module: AgusVRRuntime).
 *
 * UI-first release: the spatial shell (curved 3D windows, immersive room,
 * dock, apps) ships fully functional; the hand-tracking pipeline stays
 * compiled but dormant until the next update, per product decision.
 */
object BuildFlags {
    /** When false the hand engine never starts and hand UI is honestly labeled. */
    const val HAND_TRACKING = false
}
