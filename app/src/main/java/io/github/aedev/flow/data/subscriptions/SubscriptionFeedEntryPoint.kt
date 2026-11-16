package io.github.aedev.flow.data.subscriptions

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.withContext

/**
 * Reaches [SubscriptionFeedRepository] from Android boundaries Hilt cannot construct — currently the
 * root composable's startup refresh. Confined to this file so callers never become service locators
 * themselves.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SubscriptionFeedEntryPoint {
    fun subscriptionFeedRepository(): SubscriptionFeedRepository
}

/**
 * Tops up whatever has gone stale since the app was last open.
 *
 * Shares the repository's plan and lock with the Subscriptions screen, so a startup refresh and the
 * screen opening moments later do not fetch the same channels twice.
 */
suspend fun refreshSubscriptionsAtStartup(context: Context) {
    val repository =
        EntryPointAccessors
            .fromApplication(context.applicationContext, SubscriptionFeedEntryPoint::class.java)
            .subscriptionFeedRepository()

    withContext(PerformanceDispatcher.networkIO) {
        runCatching {
            val plan = repository.planAllRefresh(force = false)
            if (plan.isEmpty) return@runCatching
            repository.refresh(plan).collect { }
        }
    }
}
