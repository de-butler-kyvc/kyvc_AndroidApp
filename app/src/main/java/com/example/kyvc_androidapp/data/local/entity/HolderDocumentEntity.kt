package com.example.kyvc_androidapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "holder_documents",
    indices = [
        Index(value = ["documentId"], unique = true),
        Index(value = ["credentialId"]),
        Index(value = ["sdJwtJti"]),
        Index(value = ["documentType", "digestSRI"])
    ]
)
data class HolderDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val documentType: String,
    val digestSRI: String,
    val mediaType: String,
    val byteSize: Long,
    val hashInput: String,
    val encryptedBlobPath: String,
    val originalFilename: String,
    val createdAt: String,
    val importedAt: String,
    val credentialId: String? = null,
    val sdJwtJti: String? = null,
    val evidenceForJson: String = "[]"
)
