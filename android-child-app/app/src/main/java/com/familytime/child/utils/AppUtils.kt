package com.familytime.child.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Utility functions for working with apps.
 */
object AppUtils {
    private const val TAG = "AppUtils"

    /**
     * Get the display name for a package.
     */
    fun getAppName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "App name not found for package: $packageName")
            packageName // Fall back to package name
        }
    }

    /**
     * Check if a package is a system package that should be ignored.
     */
    fun isSystemPackage(packageName: String): Boolean {
        val systemPackages = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher", // Samsung launcher
            "com.android.settings",
            "com.android.packageinstaller",
            // Add our own app to ignore
            "com.familytime.child"
        )

        return systemPackages.any { packageName.contains(it, ignoreCase = true) }
            || packageName.startsWith("com.google.android.gms")
            || packageName.startsWith("android")
    }
}
