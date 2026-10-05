package com.github.tvbox.osc.data;

import androidx.room3.Dao;
import androidx.room3.Delete;
import androidx.room3.Insert;
import androidx.room3.OnConflictStrategy;
import androidx.room3.Query;

import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Dao
public interface VodCollectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VodCollect record);

    @Query("select * from vodCollect  order by updateTime desc")
    List<VodCollect> getAll();

    @Query("select * from vodCollect where `id`=:id")
    VodCollect getVodCollect(int id);

    @Query("delete from vodCollect where `id`=:id")
    void delete(int id);

    @Query("select * from vodCollect where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodCollect getVodCollect(String cid, String sourceKey, String vodId);

    @Delete
    int delete(VodCollect record);
    
    @Query("DELETE FROM vodCollect")
    void deleteAll();

}