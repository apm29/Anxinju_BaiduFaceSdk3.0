package com.apm.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.apm.data.db.entity.PassageLog
import com.apm.data.db.entity.Sync

/**
 *  author : ciih
 *  date : 2019-10-11 14:45
 *  description :
 */
@Dao
abstract class LogDao {

    @Insert(entity = PassageLog::class, onConflict = OnConflictStrategy.REPLACE)
    abstract fun addLog(data: PassageLog): Long
}