package com.github.tvbox.osc.data;

import androidx.room3.ColumnInfo;
import androidx.room3.Entity;
import androidx.room3.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "vodCollect")
public class VodCollect implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    @ColumnInfo(name = "name")
    public String name;
    @ColumnInfo(name = "pic")
    public String pic;
    /** 订阅标识(收藏时的配置地址):列表全局显示,点击时按它路由回原订阅 */
    @ColumnInfo(name = "cid")
    public String cid;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}