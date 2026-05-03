package com.example.kyvc_androidapp.di

import android.app.Application
import androidx.room.Room
import com.example.kyvc_androidapp.data.local.AppDatabase
import com.example.kyvc_androidapp.data.repository.CredentialRepository
import com.example.kyvc_androidapp.wallet.core.WalletManager
import com.example.kyvc_androidapp.wallet.core.WalletStateStore
import com.example.kyvc_androidapp.wallet.core.XrplClientHelper

class AppContainer(application: Application) {
    val walletManager: WalletManager = WalletManager()
    val xrplHelper: XrplClientHelper = XrplClientHelper()
    val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        DATABASE_NAME
    ).build()
    val credentialRepository: CredentialRepository = CredentialRepository(database.credentialDao())
    val walletStateStore: WalletStateStore = WalletStateStore(application, walletManager)

    private companion object {
        private const val DATABASE_NAME = "kyvc-wallet-db"
    }
}
