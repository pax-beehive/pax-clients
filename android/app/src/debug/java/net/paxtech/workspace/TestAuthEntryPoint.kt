package net.paxtech.workspace

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.paxtech.workspace.auth.UserAuth

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestAuthEntryPoint {
    fun auth(): UserAuth
}
