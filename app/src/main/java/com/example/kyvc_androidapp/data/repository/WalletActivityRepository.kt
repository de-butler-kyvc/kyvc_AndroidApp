package com.example.kyvc_androidapp.data.repository

import com.example.kyvc_androidapp.data.local.dao.WalletActivityDao
import com.example.kyvc_androidapp.data.local.entity.WalletActivityEntity

class WalletActivityRepository(
    private val walletActivityDao: WalletActivityDao
) {
    suspend fun insert(entity: WalletActivityEntity) {
        walletActivityDao.insert(entity)
    }

    suspend fun findRecent(limit: Int, types: List<String>): List<WalletActivityEntity> {
        return if (types.isEmpty()) {
            walletActivityDao.findRecentAll(limit = limit)
        } else {
            walletActivityDao.findRecentByTypes(limit = limit, types = types)
        }
    }

    suspend fun markRead(activityIds: List<String>) {
        if (activityIds.isEmpty()) {
            walletActivityDao.markAllRead()
        } else {
            walletActivityDao.markRead(activityIds)
        }
    }
}
