// data/local/dao/CustomModelDao.kt
package com.example.autochat.data.local.dao

import androidx.room.*
import com.example.autochat.data.local.entity.CustomModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomModelDao {
    @Query("SELECT * FROM custom_models ORDER BY addedAt DESC")
    fun getAllModels(): Flow<List<CustomModelEntity>>

    @Query("SELECT * FROM custom_models WHERE id = :id")
    suspend fun getModelById(id: String): CustomModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: CustomModelEntity)

    @Update
    suspend fun updateModel(model: CustomModelEntity)

    @Delete
    suspend fun deleteModel(model: CustomModelEntity)

    @Query("DELETE FROM custom_models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM custom_models")
    suspend fun getModelCount(): Int
}