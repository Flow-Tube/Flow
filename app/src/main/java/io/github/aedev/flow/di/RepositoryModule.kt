package io.github.aedev.flow.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.platform.memory.DeviceTier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideYouTubeRepository(
        playerPreferences: PlayerPreferences,
        deviceTier: DeviceTier,
    ): YouTubeRepository = YouTubeRepository.getInstance(playerPreferences).also { it.limitWatchCache(deviceTier.watchCacheEntries) }

    @Provides
    @Singleton
    fun provideSubscriptionRepository(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.SubscriptionRepository =
        io.github.aedev.flow.data.local.SubscriptionRepository
            .getInstance(context)

    @Provides
    @Singleton
    fun provideLikedVideosRepository(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.LikedVideosRepository =
        io.github.aedev.flow.data.local.LikedVideosRepository
            .getInstance(context)

    @Provides
    @Singleton
    fun provideViewHistory(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.ViewHistory =
        io.github.aedev.flow.data.local.ViewHistory
            .getInstance(context)

    @Provides
    @Singleton
    fun provideHomeFeedCacheRepository(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.HomeFeedCacheRepository =
        io.github.aedev.flow.data.local
            .HomeFeedCacheRepository(context)

    @Provides
    @Singleton
    fun provideMusicPlaylistRepository(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.music.PlaylistRepository =
        io.github.aedev.flow.data.music
            .PlaylistRepository(context)

    // VideoDownloadManager is now @Singleton @Inject — Hilt provides it automatically
    @Provides
    @Singleton
    fun providePlayerPreferences(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.PlayerPreferences =
        io.github.aedev.flow.data.local
            .PlayerPreferences(context)

    /** Transitional: the music player manager still reaches the queue store through getInstance, so this hands injected consumers the same instance. */
    @Provides
    @Singleton
    fun provideQueuePersistence(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.QueuePersistence =
        io.github.aedev.flow.data.local.QueuePersistence
            .getInstance(context)

    /**
     * Transitional: [io.github.aedev.flow.data.local.BackupRepository] still builds its own
     * collaborators, so it is provided here rather than injected through its constructor. One
     * instance serves the whole app.
     */
    @Provides
    @Singleton
    fun provideBackupRepository(
        @ApplicationContext context: Context,
    ): io.github.aedev.flow.data.local.BackupRepository =
        io.github.aedev.flow.data.local
            .BackupRepository(context)
}
