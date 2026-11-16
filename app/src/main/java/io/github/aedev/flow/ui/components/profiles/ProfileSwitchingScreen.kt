package io.github.aedev.flow.ui.components.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileSwitchGate
import io.github.aedev.flow.data.profile.displayName
import io.github.aedev.flow.ui.components.shared.FlowSpinner

private val SwitchingAvatarSize = 96.dp
private val ContentSpacing = 24.dp
private val ContentPadding = 32.dp

/**
 * Covers the app, in the current theme, from the moment a switch starts until the restart: the incoming profile's
 * avatar and name, and a loading indicator that exists only while this is on screen. Back does nothing meanwhile.
 * The opaque surface also keeps touches from reaching the app underneath.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileSwitchingScreen(profile: Profile?) {
    BackHandler(enabled = true) {}
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ContentPadding),
            verticalArrangement = Arrangement.spacedBy(ContentSpacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (profile != null) {
                ProfileAvatar(profile, size = SwitchingAvatarSize)
                Text(
                    text = stringResource(R.string.profiles_switching_to, profile.displayName(LocalContext.current)),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
            FlowSpinner()
        }
    }
}

/**
 * Records the running profile's splash tone, and while a switch runs covers the app with [ProfileSwitchingScreen]
 * and hands [onSplashTone] the incoming profile's tone, so the next launch opens in it.
 */
@Composable
fun ProfileSwitchingOverlay(
    currentTone: String,
    onSplashTone: suspend (String) -> Unit,
    viewModel: ProfileSwitchViewModel = hiltViewModel(),
) {
    LaunchedEffect(currentTone) { viewModel.recordSplashTone(currentTone) }
    val target by ProfileSwitchGate.target.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val id = target ?: return
    val profile = state?.profiles?.firstOrNull { it.id == id }
    val incomingTone = profile?.splashTone.orEmpty()
    LaunchedEffect(id, incomingTone) { if (incomingTone.isNotEmpty()) onSplashTone(incomingTone) }
    ProfileSwitchingScreen(profile)
}
