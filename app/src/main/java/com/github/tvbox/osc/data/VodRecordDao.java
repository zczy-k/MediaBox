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
public interface VodRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VodRecord record);

    @Query("select * from vodRecord where `cid`=:cid order by updateTime desc, id desc limit :size")
    List<VodRecord> getAll(String cid, int size);

    @Query("select * from vodRecord where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodRecord getVodRecord(String cid, String sourceKey, String vodId);

    @Delete
    int delete(VodRecord record);

    @Query("select count(*) from vodRecord where `cid`=:cid")
    int getCount(String cid);

    @Query("DELETE FROM vodRecord where `cid`=:cid")
    void deleteAll(String cid);

    /**
     * 保留最新指定条数, 其他删除.
     * @param size 保留条数
     * @return
     */
    @Query("DELETE FROM vodRecord where `cid`=:cid and id NOT IN (SELECT id FROM vodRecord WHERE `cid`=:cid ORDER BY updateTime desc, id desc LIMIT :size)")
    int reserver(String cid, int size);
}
