package com.example.kyvc_androidapp.data.repository

import com.example.kyvc_androidapp.data.local.dao.HolderDocumentDao
import com.example.kyvc_androidapp.data.local.entity.HolderDocumentEntity

class HolderDocumentRepository(
    private val holderDocumentDao: HolderDocumentDao
) {
    suspend fun upsert(entity: HolderDocumentEntity) = holderDocumentDao.insert(entity)

    suspend fun findByDocumentId(documentId: String): HolderDocumentEntity? =
        holderDocumentDao.findByDocumentId(documentId)

    suspend fun findByTypeAndDigest(documentType: String, digestSRI: String): HolderDocumentEntity? =
        holderDocumentDao.findByTypeAndDigest(documentType, digestSRI)

    suspend fun findAllByCredentialId(credentialId: String): List<HolderDocumentEntity> =
        holderDocumentDao.findAllByCredentialId(credentialId)
}
