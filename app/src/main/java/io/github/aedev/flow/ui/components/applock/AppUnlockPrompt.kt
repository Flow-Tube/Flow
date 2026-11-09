package io.github.aedev.flow.ui.components.applock

import androidx.biometric.AuthenticationRequest
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.applock.AppLock
import io.github.aedev.flow.applock.usesStrongBiometrics

/**
 * Asks for the phone's own unlock: biometrics, with the PIN, pattern or password as the fallback.
 * The library's Compose launcher needs no FragmentActivity, so MainActivity stays a ComponentActivity.
 * [onResult] gets whether it succeeded.
 */
@Composable
internal fun rememberAppUnlock(
    appLock: AppLock,
    onResult: (Boolean) -> Unit = {},
): () -> Unit {
    val title = stringResource(R.string.app_lock_prompt_title)
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher =
        rememberAuthenticationLauncher { result ->
            val succeeded = result.isSuccess()
            appLock.endAuthentication(succeeded)
            currentOnResult(succeeded)
        }
    return remember(launcher, title) {
        {
            appLock.beginAuthentication()
            launcher.launch(unlockRequest(title))
        }
    }
}

private fun unlockRequest(title: String): AuthenticationRequest =
    AuthenticationRequest.biometricRequest(title, AuthenticationRequest.Biometric.Fallback.DeviceCredential) {
        setMinStrength(
            if (usesStrongBiometrics()) {
                AuthenticationRequest.Biometric.Strength.Class3()
            } else {
                AuthenticationRequest.Biometric.Strength.Class2
            },
        )
    }
