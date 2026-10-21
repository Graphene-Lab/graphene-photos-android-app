package com.graphenelab.photosync.manager.interfaces

import android.content.Intent

interface IOAuthManager {
    fun getAuthIntent(): Intent
    suspend fun exchangeCodeForTokens(intent: Intent)
}
