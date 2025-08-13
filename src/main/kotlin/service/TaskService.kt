package cn.com.lushunming.service

import model.Task
import model.Tasks
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpsertSqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import service.DatabaseFactory

class TaskService {


    suspend fun getTaskList(): List<Task> {
        return DatabaseFactory.dbQuery {
            Tasks.selectAll().map { toTask(it) }
        }
    }


    suspend fun addTask(task: Task) {
        DatabaseFactory.dbQuery {
            Tasks.insert {
                it[Tasks.id] = task.id
                it[Tasks.name] = task.name
                it[Tasks.url] = task.url
                it[Tasks.oriUrl] = task.oriUrl
                it[Tasks.type] = task.type
                it[Tasks.downloaded] = task.downloaded
                it[Tasks.total] = task.total
            }
        }
    }

    suspend fun updateProgress(id: String, downloaded: Int, total: Int) {
        DatabaseFactory.dbQuery {
            Tasks.update(where = { Tasks.id eq id }) {
                it[Tasks.downloaded] = downloaded
                it[Tasks.total] = total

            }
        }
    }

    suspend fun getTaskById(id: String): Task? {

        return DatabaseFactory.dbQuery {
            Tasks.selectAll().where { Tasks.id eq id }.map { toTask(it) }.singleOrNull()
        }
    }

    private fun toTask(row: ResultRow): Task = Task(
        id = row[Tasks.id], name = row[Tasks.name], url = row[Tasks.url], type = row[Tasks.type],
        downloaded = row[Tasks.downloaded],
        total = row[Tasks.total],
        oriUrl = row[Tasks.oriUrl],
    )

    suspend fun deleteTask(id: String?) {
        id ?: return
        return DatabaseFactory.dbQuery {
            Tasks.deleteWhere { Tasks.id eq id }
        }

    }
}