package com.example.kyvc_androidapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val credentialId: String,
    val vcJson: String,
    val issuerDid: String,
    val issuerAccount: String,
    val holderDid: String,
    val holderAccount: String,
    val credentialType: String,
    val vcCoreHash: String,
    val validFrom: String,
    val validUntil: String,
    val acceptedAt: String? = null,
    val credentialAcceptHash: String? = null,
    val revokedOrInactiveAt: String? = null
)
