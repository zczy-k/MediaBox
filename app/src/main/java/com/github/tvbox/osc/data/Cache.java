package com.github.tvbox.osc.data;

import androidx.annotation.NonNull;
import androidx.room3.Entity;
import androidx.room3.PrimaryKey;

import java.io.Serializable;

/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Entity(tableName = "cache")
public class Cache implements Serializable {
    @PrimaryKey(autoGenerate = false)
    @NonNull
    public String key;
    public byte[] data;
}
