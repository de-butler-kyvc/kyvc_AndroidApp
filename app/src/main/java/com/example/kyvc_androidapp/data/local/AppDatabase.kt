package com.example.kyvc_androidapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.kyvc_androidapp.data.local.dao.CredentialDao
import com.example.kyvc_androidapp.data.local.dao.HolderDocumentDao
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import com.example.kyvc_androidapp.data.local.entity.HolderDocumentEntity

@Database(
    entities = [CredentialEntity::class, HolderDocumentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun holderDocumentDao(): HolderDocumentDao
}
