package com.agentdesk.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.agentdesk.app.navigation.AppNavHost
import com.agentdesk.app.navigation.Routes
import com.agentdesk.app.ui.lock.LockScreen
import com.agentdesk.app.ui.theme.AgentDeskTheme
import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.persistence.dao.ConsentDao
import com.agentdesk.core.security.AppLockManager
import com.agentdesk.core.settings.ConsentKeys
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
 * 4. Determine startDestination based on user consent (first run vs returning).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var consentDao: ConsentDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentDeskTheme {
                AgentDeskRoot(
                    appLockManager = appLockManager,
                    consentDao = consentDao,
                    activity = this
                )
            }
        }
    }
}

@Composable
private fun AgentDeskRoot(
    appLockManager: AppLockManager,
    consentDao: ConsentDao,
    activity: FragmentActivity
) {
    var isLockChecked by rememberSaveable { mutableStateOf(false) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var lockError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var startDestination by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!isLockChecked) {
            val lockEnabled = appLockManager.isLockEnabledAsync()
            if (lockEnabled) {
                isLocked = true
            }
            isLockChecked = true
        }

        if (startDestination == null) {
            val consentState = consentDao.getState(ConsentKeys.AUDIT_LOG)
            startDestination = if (consentState == ConsentState.GRANTED) {
                Routes.CHAT
            } else {
                Routes.ONBOARDING
            }
        }
    }

    if (!isLockChecked || startDestination == null) {
        // Safe empty container while initial security status and start destination are resolved
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {}
    } else if (isLocked) {
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
        AppNavHost(startDestination = startDestination!!)
    }
}
