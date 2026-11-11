package com.yehor.lightwatch

import android.content.Context

/**
 * Hook for the host app's own surfaces. Each app sets [onChange] in its Application.onCreate,
 * which runs before any worker, receiver or service in the process.
 */
object Surfaces {
    /** Called after the cached schedule or the error changed, or a display setting changed. */
    @Volatile var onChange: ((Context) -> Unit)? = null
}
