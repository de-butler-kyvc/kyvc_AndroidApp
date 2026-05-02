package com.example.kyvc_androidapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.kyvc_androidapp.data.local.dao.CredentialDao
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity

@Database(entities = [CredentialEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
}
