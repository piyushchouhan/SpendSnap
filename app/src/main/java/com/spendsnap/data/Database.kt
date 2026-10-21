package com.spendsnap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE reviewIssues != '' ORDER BY timestamp DESC")
    fun observeReviewQueue(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE transactionReference = :reference LIMIT 1")
    suspend fun findByReference(reference: String): Transaction?

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :from AND :until")
    suspend fun findBetween(from: Long, until: Long): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY timestamp ASC")
    suspend fun getAll(): List<Transaction>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /** Entries that pointed at a deleted row must not stay hidden from totals. */
    @Query("UPDATE transactions SET possibleDuplicateOfId = NULL WHERE possibleDuplicateOfId = :id")
    suspend fun clearDuplicateLinksTo(id: Long)
}

class Converters {
    @TypeConverter
    fun issuesToString(issues: Set<ReviewIssue>): String = issues.map { it.name }.sorted().joinToString(",")

    @TypeConverter
    fun stringToIssues(value: String): Set<ReviewIssue> =
        value.split(',').filter { it.isNotBlank() }
            .mapNotNull { name -> ReviewIssue.entries.firstOrNull { it.name == name } }
            .toSet()
}

@Database(entities = [Transaction::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spendsnap.db").build()
    }
}
