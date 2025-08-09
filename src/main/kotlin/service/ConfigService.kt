package cn.com.lushunming.service

import cn.com.lushunming.model.AppConfig
import cn.com.lushunming.model.Config
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import service.DatabaseFactory

class ConfigService {


    suspend fun getConfig(): AppConfig? {
        return DatabaseFactory.dbQuery {
            Config.selectAll().map { toAppConfig(it) }.firstOrNull()
        }
    }


    suspend fun saveConfig(config: AppConfig) {
        DatabaseFactory.dbQuery {
            val old =  Config.selectAll().map { toAppConfig(it) }.firstOrNull()
            if (old == null) {
                Config.insert {
                    it[Config.proxy] = config.proxy
                    it[Config.open] = config.open
                }
            } else {
                Config.update({ Config.id eq old.id!! }) {
                    it[Config.proxy] = config.proxy
                    it[Config.open] = config.open
                }

            }
        }
    }

    private fun toAppConfig(row: ResultRow): AppConfig = AppConfig(
        proxy = row[Config.proxy],
        open = row[Config.open],
        id = row[Config.id],
    )
}