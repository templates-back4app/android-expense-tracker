package com.back4app.expensetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EntryEntity::class, OpEntity::class, ConflictEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun ops(): OpDao
    abstract fun conflicts(): ConflictDao
    abstract fun categories(): CategoryDao

    companion object {
        const val NAME = "expense-tracker.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
