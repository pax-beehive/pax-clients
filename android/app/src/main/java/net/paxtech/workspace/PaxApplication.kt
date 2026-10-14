package net.paxtech.workspace

import android.app.Application
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.paxtech.workspace.data.SecureVault
import net.paxtech.workspace.data.Vault

@HiltAndroidApp class PaxApplication : Application()

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds @Singleton abstract fun vault(implementation: SecureVault): Vault
}
