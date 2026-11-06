package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object IntegrationsIndex {
    private val page = SettingsDestination.INTEGRATIONS

    val sponsorBlock =
        SettingEntry(
            key = "integrations.sponsorblock",
            title = R.string.player_settings_sponsorblock,
            summary = R.string.player_settings_sponsorblock_subtitle,
            keywords = R.string.settings_keywords_sponsorblock,
            destination = page,
        )
    val segments =
        SettingEntry(
            key = "integrations.sponsorblock.segments",
            title = R.string.sb_segments_header,
            keywords = R.string.settings_keywords_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val contribute =
        SettingEntry(
            key = "integrations.sponsorblock.contribute",
            title = R.string.sb_contribute_toggle_title,
            summary = R.string.sb_contribute_toggle_subtitle,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val skipNotice =
        SettingEntry(
            key = "integrations.sponsorblock.skip_notice",
            title = R.string.sb_skip_notice_title,
            summary = R.string.sb_skip_notice_summary,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val skipButtonStyle =
        SettingEntry(
            key = "integrations.sponsorblock.skip_button_style",
            title = R.string.sb_skip_style_title,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val userId =
        SettingEntry(
            key = "integrations.sponsorblock.user_id",
            title = R.string.sb_user_id_title,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val deArrow =
        SettingEntry(
            key = "integrations.dearrow",
            title = R.string.player_settings_dearrow,
            summary = R.string.player_settings_dearrow_subtitle,
            keywords = R.string.settings_keywords_dearrow,
            destination = page,
        )
    val deArrowRandomFrame =
        SettingEntry(
            key = "integrations.dearrow.random_frame",
            title = R.string.dearrow_random_frame,
            summary = R.string.dearrow_random_frame_subtitle,
            section = R.string.player_settings_dearrow,
            destination = page,
        )
    val deArrowBadge =
        SettingEntry(
            key = "integrations.dearrow.badge",
            title = R.string.dearrow_badge_toggle,
            summary = R.string.dearrow_badge_toggle_subtitle,
            section = R.string.player_settings_dearrow,
            destination = page,
        )
    val dislikes =
        SettingEntry(
            key = "integrations.dislikes",
            title = R.string.player_settings_rytd_title,
            summary = R.string.player_settings_rytd_subtitle,
            destination = page,
        )
    val discord =
        SettingEntry(
            key = "integrations.discord",
            title = R.string.discord_presence_enable,
            summary = R.string.discord_presence_enable_description,
            keywords = R.string.settings_keywords_discord,
            section = R.string.discord_presence_title,
            availability = SettingAvailability.GithubOnly,
            destination = page,
        )
    val discordAccount =
        SettingEntry(
            key = "integrations.discord.account",
            title = R.string.discord_presence_account,
            keywords = R.string.settings_keywords_discord,
            section = R.string.discord_presence_title,
            availability = SettingAvailability.GithubOnly,
            destination = page,
        )

    val all =
        listOf(
            sponsorBlock,
            segments,
            skipNotice,
            skipButtonStyle,
            contribute,
            userId,
            deArrow,
            deArrowRandomFrame,
            deArrowBadge,
            dislikes,
            discord,
            discordAccount,
        )
}
