package com.example.kyvc_androidapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.kyvc_androidapp.data.local.entity.HolderDocumentEntity

@Dao
interface HolderDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HolderDocumentEntity)

    @Query("SELECT * FROM holder_documents WHERE documentId = :documentId LIMIT 1")
    suspend fun findByDocumentId(documentId: String): HolderDocumentEntity?

    @Query(
        "SELECT * FROM holder_documents WHERE documentType = :documentType AND digestSRI = :digestSRI " +
            "ORDER BY importedAt DESC LIMIT 1"
    )
    suspend fun findByTypeAndDigest(documentType: String, digestSRI: String): HolderDocumentEntity?

    @Query("SELECT * FROM holder_documents WHERE credentialId = :credentialId ORDER BY importedAt DESC")
    suspend fun findAllByCredentialId(credentialId: String): List<HolderDocumentEntity>
}
