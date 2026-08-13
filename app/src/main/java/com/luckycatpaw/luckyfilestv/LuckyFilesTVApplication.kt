package com.luckycatpaw.luckyfilestv

import android.app.Application
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.repository.ImageRepository
import com.luckycatpaw.luckyfilestv.data.transfer.ReplacementTransactionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LuckyFilesTVApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        ImageRepository.get(this)
        applicationScope.launch {
            ReplacementTransactionStore(
                context = this@LuckyFilesTVApplication,
                fileTreeWalker = FileTreeWalker()
            ).recoverPending()
        }
    }
}
