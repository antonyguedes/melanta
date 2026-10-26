package com.melanta.core.ai.model

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.ceil

class DeviceMemory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * RAM total em GB "de caixa". Aparelhos anunciam GiB (8 GB = 8 GiB) e o sistema reserva uma
     * parte, então um de 8 GB reporta ~7,5 GiB. Arredondar para cima em GiB devolve o anunciado.
     */
    val totalRamGb: Int by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        ceil(info.totalMem / GIB).toInt()
    }

    private companion object {
        const val GIB = 1024.0 * 1024 * 1024
    }

    fun fits(spec: ModelSpec): Boolean = totalRamGb >= spec.recommendedRamGb
}
