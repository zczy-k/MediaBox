package com.github.tvbox.osc.data;

import androidx.room3.Database;
import androidx.room3.migration.Migration;
import androidx.room3.sqlite.db.SupportSQLiteDatabase;
import androidx.room3.RoomDatabase;



/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Database(entities = {Cache.class, VodRecord.class, VodCollect.class}, version = 2)
public abstract class AppDataBase extends RoomDatabase {
    /** v2:收藏表加 type 列(收藏页分类胶囊)。老数据 type 为 null,页面归入「电影」兜底 */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE VodCollect ADD COLUMN type TEXT");
        }
    };
    public abstract CacheDao getCacheDao();

    public abstract VodRecordDao getVodRecordDao();

    public abstract VodCollectDao getVodCollectDao();
}
