package com.forgerom.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forgerom.app.ui.Actions
import com.forgerom.app.ui.ForgeRomApp
import com.forgerom.app.ui.ForgeRomTheme

// AppCompatActivity (not ComponentActivity) is required for the in-app language switch on older Android versions.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The status bar sits on top of the blue gradient, so keep its icons light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            ForgeRomTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.verifyDownloads()
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
                ForgeRomApp(
                    state = state,
                    a = Actions(
                        onApiKeyChange = vm::setApiKey,
                        onSearch = vm::search,
                        onSearchAi = vm::searchAi,
                        onOpenPicker = vm::openPicker,
                        onClosePicker = vm::closePicker,
                        onPickerQuery = vm::onPickerQuery,
                        onSelectManual = vm::selectManual,
                        onUseDetected = vm::useDetected,
                        onOpenSettings = vm::openSettings,
                        onCloseSettings = vm::closeSettings,
                        onSetMode = vm::setMode,
                        onCompleteOnboarding = vm::completeOnboarding,
                        onRecheckRoot = vm::recheckRoot,
                        onDismissNotice = vm::dismissNotice,
                        onSetLanguage = Languages::setLanguage,
                        onDownload = vm::download,
                        onOpenGuide = vm::openGuide,
                        onCloseGuide = vm::closeGuide,
                        onDismissUpdate = vm::dismissUpdate,
                        onInstallVerifiedUpdate = vm::installVerifiedUpdate,
                        onDismissCrash = vm::dismissCrash,
                        onSetUpdateCheck = vm::setUpdateCheck,
                        onRefreshCatalog = vm::refreshCatalog,
                        onRefreshHardware = vm::refreshHardware,
                        onVerifyDownloads = vm::verifyDownloads,
                        onPrepareRecoveryZip = vm::prepareRecoveryZip,
                        onRebootRecovery = vm::rebootRecovery
                    )
                )
            }
        }
    }
}
