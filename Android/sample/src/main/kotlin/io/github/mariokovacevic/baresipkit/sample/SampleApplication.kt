package io.github.mariokovacevic.baresipkit.sample

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.mariokovacevic.baresipkit.BaresipEngine
import io.github.mariokovacevic.baresipkit.BaresipFileLog
import io.github.mariokovacevic.baresipkit.sample.model.CallModel
import io.github.mariokovacevic.baresipkit.sample.model.PushReceiver
import io.github.mariokovacevic.baresipkit.sample.model.SettingsStore
import io.github.mariokovacevic.baresipkit.sample.model.movedToBackground
import io.github.mariokovacevic.baresipkit.sample.model.movedToForeground
import io.github.mariokovacevic.baresipkit.sample.system.AndroidCallSystem
import io.github.mariokovacevic.baresipkit.sample.system.KeystoreSecretStore
import io.github.mariokovacevic.baresipkit.sample.system.PreferencesStore
import io.github.mariokovacevic.baresipkit.video.BaresipCamera
import java.io.File

class SampleApplication : Application() {
    lateinit var model: CallModel
        private set

    val push: PushReceiver
        get() = model

    override fun onCreate() {
        super.onCreate()
        BaresipCamera.install(this)
        val log = BaresipFileLog(File(filesDir, "Logs"))
        val store =
            SettingsStore(
                PreferencesStore(getSharedPreferences("sample", MODE_PRIVATE)),
                KeystoreSecretStore(getSharedPreferences("sample.secrets", MODE_PRIVATE)),
                logsSipMessagesByDefault = BuildConfig.DEBUG,
            )
        model = CallModel(this, store, log, AndroidCallSystem(this), BuildConfig.DEBUG)
        log.write(
            "launched $packageName ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE}, " +
                "BaresipKit core ${runCatching { BaresipEngine.coreVersion }.getOrDefault("unknown")}",
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = model.movedToForeground()

                override fun onStop(owner: LifecycleOwner) = model.movedToBackground()
            },
        )
    }
}
