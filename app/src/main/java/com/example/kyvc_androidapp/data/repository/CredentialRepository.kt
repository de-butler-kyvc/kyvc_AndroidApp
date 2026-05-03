package com.example.kyvc_androidapp.data.repository

import com.example.kyvc_androidapp.data.local.dao.CredentialDao
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

class CredentialRepository(
    private val credentialDao: CredentialDao
) {
    fun observeCredentials(): Flow<List<CredentialEntity>> {
        return credentialDao.getAllCredentials()
    }

    suspend fun getCredentialById(id: String): CredentialEntity? {
        return credentialDao.getCredentialById(id)
    }

    suspend fun getAllCredentialsOnce(): List<CredentialEntity> {
        return credentialDao.getAllCredentialsOnce()
    }

    suspend fun insertCredential(credential: CredentialEntity) {
        credentialDao.insertCredential(credential)
    }

    suspend fun updateCredential(credential: CredentialEntity) {
        credentialDao.updateCredential(credential)
    }

    suspend fun deleteCredential(credential: CredentialEntity) {
        credentialDao.deleteCredential(credential)
    }
}
