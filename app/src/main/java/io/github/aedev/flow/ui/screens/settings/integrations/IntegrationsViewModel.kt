package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.discord.DiscordLinkResult
import io.github.aedev.flow.discord.DiscordPresenceController
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** One segment category's behaviour and colour; a null [colorArgb] means the default colour. */
@Immutable
data class SegmentSetting(
    val action: SponsorBlockAction,
    val colorArgb: Int?,
)

@HiltViewModel
class IntegrationsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val discord: DiscordPresenceController,
    ) : SettingsViewModel() {
        val sponsorBlock = preferences.sponsorBlockEnabled.asState(false)
        val submitButton = preferences.sbSubmitEnabled.asState(false)
        val userId = preferences.sbUserId.asState(null)
        val skipNotice = preferences.sbSkipNoticeEnabled.asState(true)
        val skipButtonStyle = preferences.sbSkipButtonStyle.asState(SponsorBlockSkipButtonStyle.FULL)
        val deArrow = preferences.deArrowEnabled.asState(false)
        val deArrowBadge = preferences.deArrowBadgeEnabled.asState(false)
        val deArrowRandomFrame = preferences.deArrowRandomFrame.asState(true)
        val dislikes = preferences.rytdEnabled.asState(true)
        val discordState = discord.settingsState

        /** Every category's flows are built once here, not per recomposition. */
        val segments =
            combine(
                SponsorBlockCategories.all.map { category ->
                    combine(preferences.sbActionForCategory(category), preferences.sbColorForCategory(category)) { action, color ->
                        category to SegmentSetting(action, color)
                    }
                },
            ) { pairs -> pairs.toMap() }
                .asState(
                    SponsorBlockCategories.all.associateWith { SegmentSetting(SponsorBlockCategories.defaultAction(it), null) },
                )

        private val _discordFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)

        /** The message of a Discord action that failed, for the page to show once. */
        val discordFailures: SharedFlow<String> = _discordFailures.asSharedFlow()

        fun setSponsorBlock(value: Boolean) = write { preferences.setSponsorBlockEnabled(value) }

        fun setSegmentAction(
            category: String,
            action: SponsorBlockAction,
        ) = write { preferences.setSbActionForCategory(category, action) }

        fun setSegmentColor(
            category: String,
            colorArgb: Int?,
        ) = write { preferences.setSbColorForCategory(category, colorArgb) }

        fun setSubmitButton(value: Boolean) = write { preferences.setSbSubmitEnabled(value) }

        fun setSkipNotice(value: Boolean) = write { preferences.setSbSkipNoticeEnabled(value) }

        fun setSkipButtonStyle(style: SponsorBlockSkipButtonStyle) = write { preferences.setSbSkipButtonStyle(style) }

        /** A blank id asks for a fresh one rather than clearing the identity. */
        fun setUserId(input: String) =
            write {
                preferences.setSbUserId(input.trim().ifBlank { preferences.getOrCreateSbUserId() })
            }

        fun setDeArrow(value: Boolean) = write { preferences.setDeArrowEnabled(value) }

        fun setDeArrowBadge(value: Boolean) = write { preferences.setDeArrowBadgeEnabled(value) }

        fun setDeArrowRandomFrame(value: Boolean) = write { preferences.setDeArrowRandomFrame(value) }

        fun setDislikes(value: Boolean) = write { preferences.setRytdEnabled(value) }

        fun setDiscordEnabled(value: Boolean) = write { discord.setEnabled(value) }

        fun connectDiscord() = write { report(discord.connectAccount()) }

        fun retryDiscord() = write { report(discord.retry()) }

        fun unlinkDiscord() = write { discord.unlink() }

        private fun report(result: DiscordLinkResult) {
            if (result is DiscordLinkResult.Failure && result.message.isNotBlank()) _discordFailures.tryEmit(result.message)
        }
    }
