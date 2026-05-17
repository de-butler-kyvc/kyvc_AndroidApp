package com.example.kyvc_androidapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wallet_activities",
    indices = [
        Index(value = ["createdAtUtc"], name = "index_wallet_activities_createdAtUtc"),
        Index(value = ["type"], name = "index_wallet_activities_type")
    ]
)
data class WalletActivityEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val description: String,
    val credentialId: String?,
    val credentialType: String?,
    val issuerDid: String?,
    val issuerName: String?,
    val verifierName: String?,
    val createdAtUtc: String,
    val unread: Boolean
)
