package com.example.kyvc_androidapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val credentialId: String,
    val format: String,
    val sdJwt: String? = null,
    val vcJwt: String? = null,
    val vcJson: String? = null,
    val selectiveDisclosureJson: String? = null,
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
