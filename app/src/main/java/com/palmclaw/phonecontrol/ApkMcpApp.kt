package com.palmclaw.phonecontrol

import android.content.Context

/**
 * Context holder for the ported apkmcp module.
 * Initialized from PalmClawApplication — ported code accesses `ApkMcpApp.appContext`.
 */
object ApkMcpApp {
    lateinit var appContext: Context
}
