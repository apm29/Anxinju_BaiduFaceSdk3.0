package com.apm.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 *  author : ciih
 *  date : 2019-10-11 14:43
 *  description :
 */
@Entity(tableName = "t_sync_time", primaryKeys = ["id"])
data class Sync(
    @ColumnInfo val id: Long,
    @ColumnInfo val lastSyncTime_face: Long,
    @ColumnInfo val lastSyncTime_rfid: Long
)