package com.github.tvbox.osc.data;

import androidx.room3.Room;
import androidx.room3.RoomDatabase;
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;

import com.github.tvbox.osc.util.FileUtils;

import java.io.File;
import java.io.IOException;
import com.github.tvbox.osc.util.AppContextHolder;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class AppDataManager {
    private static final int DB_FILE_VERSION = 4;
    private static final String DB_NAME = "tvbox";
    private static AppDataManager manager;
    private static AppDataBase dbInstance;

    private AppDataManager() {
    }

    public static void init() {
        if (manager == null) {
            synchronized (AppDataManager.class) {
                if (manager == null) {
                    manager = new AppDataManager();
                }
            }
        }
    }

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    /**
     * 获取（或重建）数据库实例。
     * 注意：backup/restore 会 close 实例并置 null，下次调用时重建；
     * 加 synchronized 防 check-then-act 竞态导致重复 build。
     */
    public static synchronized AppDataBase get() {
        if (manager == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        if (dbInstance == null)
            dbInstance = Room.databaseBuilder(AppContextHolder.context(), AppDataBase.class, dbPath())
                    // Room 3：必须显式指定 SQLiteDriver（不再走 SupportSQLite）
                    .setDriver(new BundledSQLiteDriver())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .allowMainThreadQueries()//可以在主线程操作
                    .build();
        return dbInstance;
    }

    public static boolean backup(File path) throws IOException {
        if (dbInstance != null) {
            dbInstance.close();
        }
        // 关闭后置 null，否则 get() 永远返回已关闭实例，后续 Room 操作全部抛
        // "connection pool has been closed"
        dbInstance = null;
        File db = AppContextHolder.context().getDatabasePath(dbPath());
        if (db.exists()) {
            FileUtils.copyFile(db, path);
            return true;
        } else {
            return false;
        }
    }

    public static boolean restore(File path) throws IOException {
        if (dbInstance != null) {
            dbInstance.close();
        }
        dbInstance = null;
        File db = AppContextHolder.context().getDatabasePath(dbPath());
        if (db.exists()) {
            db.delete();
        }
        if (!db.getParentFile().exists())
            db.getParentFile().mkdirs();
        FileUtils.copyFile(path, db);
        return true;
    }
}
