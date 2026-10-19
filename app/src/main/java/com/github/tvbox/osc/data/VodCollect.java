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
    /** 源站分类原文(动作片/连续剧/综艺…):收藏页分类胶囊的过滤依据,v2 迁移新增,老数据为 null */
    @ColumnInfo(name = "type")
    public String type;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}