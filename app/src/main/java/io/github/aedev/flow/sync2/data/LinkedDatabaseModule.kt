package io.github.aedev.flow.sync2.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.sync2.store.LocalStore
import java.io.File
import javax.inject.Singleton

/** One copy per device, shared by every profile: the group belongs to the device, not to a profile. */
@Module
@InstallIn(SingletonComponent::class)
object LinkedDatabaseModule {
    @Provides
    @Singleton
    fun provideLinkedDatabase(
        @ApplicationContext context: Context,
    ): LinkedDatabase =
        Room
            .databaseBuilder(context, LinkedDatabase::class.java, "linked_devices")
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .addCallback(ForgetHeldOnReset(store(context)))
            .build()

    @Provides
    @Singleton
    fun provideLocalStore(
        @ApplicationContext context: Context,
        db: LinkedDatabase,
    ): LocalStore = LocalStore(store(context), db.held())

    @Provides
    @Singleton
    fun provideKeptBatches(
        @ApplicationContext context: Context,
        db: LinkedDatabase,
    ): KeptBatches = KeptBatches(File(store(context), "kept"), db.profiles())

    private fun store(context: Context) = ProfileFiles.file(context, StoreScope.DEVICE, "linked_store")
}

/**
 * An older build that finds a newer schema starts the database empty (#1854). Linking again makes fresh
 * keys, so only what the device held of its old group has to go with it.
 */
internal class ForgetHeldOnReset(
    private val root: File,
) : RoomDatabase.Callback() {
    override fun onDestructiveMigration(connection: SQLiteConnection) {
        root.deleteRecursively()
    }
}
