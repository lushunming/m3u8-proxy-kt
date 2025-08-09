package db.migration

import cn.com.lushunming.model.Config
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V1_3__ConfigUpdate : BaseJavaMigration() {
    override fun migrate(context: Context?) {
        transaction {
            SchemaUtils.drop(Config)
            SchemaUtils.create(Config)

        }
    }
}