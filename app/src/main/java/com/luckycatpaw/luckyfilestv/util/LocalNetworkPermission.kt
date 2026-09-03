package com.luckycatpaw.luckyfilestv.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Access to devices on the local network.
 *
 * Up to Android 16 the `INTERNET` permission covered this implicitly. Apps targeting
 * Android 17 have to ask for it separately: without the grant the system refuses every
 * socket to a local address, and it does so at connect time with an ordinary socket error.
 * Nothing in the failure says "permission" — which is exactly why this has to be checked
 * before connecting rather than diagnosed afterwards.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** API level of Android 17, spelled out because the build may compile against an older SDK. */
private const val ANDROID_17 = 37

fun Context.hasLocalNetworkAccess(): Boolean = Build.VERSION.SDK_INT < ANDROID_17 ||
    checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
