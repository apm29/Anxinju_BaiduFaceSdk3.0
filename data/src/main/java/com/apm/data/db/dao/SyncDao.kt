package com.apm.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.apm.data.db.entity.Sync

/**
 *  author : ciih
 *  date : 2019-10-11 14:45
 *  description :
 */
@Dao
abstract class SyncDao {

    @Query("SELECT * from t_sync_time where id = 0")
    abstract fun getSync(): Sync?

    fun getFaceLastSyncTime(): Long {
        checkSyncNotNull()
        return getSync()?.lastSyncTime_face ?: 0
    }

    fun getRFIDLastSyncTime(): Long {
        checkSyncNotNull()
        return getSync()?.lastSyncTime_rfid ?: 0
    }

    @Query("UPDATE t_sync_time SET lastSyncTime_face = :time WHERE id = 0")
    abstract fun resetFaceLastSyncTime(time:Long)

    @Query("UPDATE t_sync_time SET lastSyncTime_rfid = :time WHERE id = 0")
    abstract fun resetRFIDLastSyncTime(time:Long)

    private fun checkSyncNotNull() {
        val sync = getSync()
        if (sync == null) {
            addSync(Sync(0, 0, 0))
        }
    }


    @Insert(entity = Sync::class, onConflict = OnConflictStrategy.REPLACE)
    abstract fun addSync(data: Sync)
}