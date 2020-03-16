package com.apm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.apm.data.db.dao.LogDao
import com.apm.data.db.dao.SyncDao
import com.apm.data.db.entity.PassageLog
import com.apm.data.db.entity.Sync


/**
 *  author : ciih
 *  date : 2019-10-11 14:40
 *  description :
 */

@Database(entities = [Sync::class, PassageLog::class], version = 2)
abstract class FaceDBManager : RoomDatabase() {

    companion object {
        private const val DATA_BASE_NAME = "sync.db"
        fun getInstance(context: Context): FaceDBManager {
            return Room.databaseBuilder(
                context, FaceDBManager::class.java,
                DATA_BASE_NAME
            )
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
        }
    }

    abstract fun getSyncDao(): SyncDao
    abstract fun getLogDao(): LogDao
}

