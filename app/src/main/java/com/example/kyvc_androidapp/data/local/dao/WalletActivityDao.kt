package com.example.kyvc_androidapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.kyvc_androidapp.data.local.entity.WalletActivityEntity

@Dao
interface WalletActivityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WalletActivityEntity)

    @Query("SELECT * FROM wallet_activities ORDER BY createdAtUtc DESC LIMIT :limit")
    suspend fun findRecentAll(limit: Int): List<WalletActivityEntity>

    @Query(
        """
        SELECT * FROM wallet_activities
        WHERE type IN (:types)
        ORDER BY createdAtUtc DESC
        LIMIT :limit
        """
    )
    suspend fun findRecentByTypes(limit: Int, types: List<String>): List<WalletActivityEntity>

    @Query("UPDATE wallet_activities SET unread = 0 WHERE id IN (:activityIds)")
    suspend fun markRead(activityIds: List<String>)

    @Query("UPDATE wallet_activities SET unread = 0")
    suspend fun markAllRead()
}
