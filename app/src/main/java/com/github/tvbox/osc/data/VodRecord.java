package com.github.tvbox.osc.data;

import androidx.room3.ColumnInfo;
import androidx.room3.Entity;
import androidx.room3.PrimaryKey;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Entity(tableName = "vodRecord")
public class VodRecord implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    /** 订阅标识(当前生效的配置地址):历史按它隔离,换订阅后旧订阅的历史不再列出 */
    @ColumnInfo(name = "cid")
    public String cid;
    public String dataJson;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}