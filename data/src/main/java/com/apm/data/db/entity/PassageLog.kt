package com.apm.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.apm.data.db.converter.Converters
import java.util.*

/**
 *  author : ciih
 *  date : 2019-10-11 14:43
 *  description :
 */
@Entity(tableName = "t_passage_log")
@TypeConverters(Converters::class)
data class PassageLog(
    @ColumnInfo val uploadTime: Date,
    @ColumnInfo val isUploaded: Boolean,
    @ColumnInfo val personId: String,
    @ColumnInfo val personName: String,
    @ColumnInfo val imageUrl: String
) {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo
    var id: Long = 0
}