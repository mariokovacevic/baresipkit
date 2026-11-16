package io.github.mariokovacevic.baresipkit.sample.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mariokovacevic.baresipkit.sample.SampleApplication
import io.github.mariokovacevic.baresipkit.sample.model.hangUp
import io.github.mariokovacevic.baresipkit.sample.model.reject
import kotlinx.coroutines.launch

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val model = (context.applicationContext as SampleApplication).model
        when (intent.action) {
            ACTION_DECLINE -> model.scope.launch { model.reject() }
            ACTION_HANG_UP -> model.scope.launch { model.hangUp() }
        }
    }

    companion object {
        const val ACTION_DECLINE = "io.github.mariokovacevic.baresipkit.sample.DECLINE"
        const val ACTION_HANG_UP = "io.github.mariokovacevic.baresipkit.sample.HANG_UP"
    }
}
