package com.codex.snapfloat

import android.content.Intent

object CapturePermissionStore {
    var resultCode: Int? = null
    var dataIntent: Intent? = null

    fun update(resultCode: Int, dataIntent: Intent) {
        this.resultCode = resultCode
        this.dataIntent = Intent(dataIntent)
    }

    fun clear() {
        resultCode = null
        dataIntent = null
    }

    fun hasPermission(): Boolean = resultCode != null && dataIntent != null
}
