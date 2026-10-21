package com.github.tvbox.osc.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Room 迁移集合。
 *
 * <p>room3 的 [Migration.migrate] 是 suspend 方法(参数是 [SQLiteConnection],不是旧的 SupportSQLiteDatabase),
 * Java 匿名类实现不了 —— 所以迁移全部写在 Kotlin 这边。
 */
object AppMigrations {

    /** v2:收藏表加 type 列(收藏页分类胶囊)。老数据 type 为 NULL,页面按「电影」兜底 */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // SQLiteConnection 没有 execSQL:DDL 也走 prepare + step,用完必须 close
            val stmt = connection.prepare("ALTER TABLE VodCollect ADD COLUMN type TEXT")
            try {
                stmt.step()
            } finally {
                stmt.close()
            }
        }
    }

    /** @JvmField:Java 侧要直接写 AppMigrations.ALL,没有它 Kotlin 只会生成 getter */
    @JvmField
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
