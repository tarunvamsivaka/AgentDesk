package com.agentdesk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import com.agentdesk.app.navigation.AppNavHost
import com.agentdesk.app.ui.lock.LockScreen
import com.agentdesk.app.ui.theme.AgentDeskTheme
import com.agentdesk.core.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainActivity — single-activity host.
 *
 * Responsibilities:
 * 1. Apply AgentDeskTheme.
 * 2. If appLockEnabled, show LockScreen before AppNavHost.
 * 3. Trigger biometric authentication via AppLockManager on request.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentDeskTheme {
                AgentDeskRoot(
                    appLockManager = appLockManager,
                    activity = this
                )
            }
        }
    }
}

@Composable
private fun AgentDeskRoot(
    appLockManager: AppLockManager,
    activity: FragmentActivity
) {
    var isLocked by remember { mutableStateOf(appLockManager.isLockEnabled()) }
    var lockError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (isLocked) {
        LockScreen(
            onBiometricRequest = {
                appLockManager.authenticate(
                    activity = activity,
                    onSuccess = { isLocked = false; lockError = null },
                    onFailure = { lockError = "Authentication failed. Try again." },
                    onError = { msg -> lockError = msg }
                )
            },
            onPinSubmit = { pin ->
                scope.launch {
                    if (appLockManager.verifyPin(pin)) {
                        isLocked = false
                        lockError = null
                    } else {
                        lockError = "Incorrect PIN."
                    }
                }
            },
            errorMessage = lockError
        )
    } else {
        AppNavHost()
    }
}
