package com.example.kyvc_androidapp.di

import android.app.Application
import androidx.room.Room
import com.example.kyvc_androidapp.data.local.AppDatabase
import com.example.kyvc_androidapp.data.repository.CredentialRepository
import com.example.kyvc_androidapp.data.repository.HolderDocumentRepository
import com.example.kyvc_androidapp.security.AppLockStore
import com.example.kyvc_androidapp.security.SecureDocumentStore
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
    )
        .addMigrations(AppDatabase.MIGRATION_2_3)
        .build()
    val credentialRepository: CredentialRepository = CredentialRepository(database.credentialDao())
    val holderDocumentRepository: HolderDocumentRepository = HolderDocumentRepository(database.holderDocumentDao())
    val secureDocumentStore: SecureDocumentStore = SecureDocumentStore(application)
    val walletStateStore: WalletStateStore = WalletStateStore(application, walletManager)
    val appLockStore: AppLockStore = AppLockStore(application)

    private companion object {
        private const val DATABASE_NAME = "kyvc-wallet-db"
    }
}
