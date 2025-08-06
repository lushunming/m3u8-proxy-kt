package cn.com.lushunming.service

import model.Task
import model.Tasks
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
                it[Tasks.name] = task.name
                it[Tasks.url] = task.url
                it[Tasks.type] = task.type
            }
        }
    }

    suspend fun getTaskById(id: Int): Task? {

        return DatabaseFactory.dbQuery {
            Tasks.selectAll().where { Tasks.id eq id }.map { toTask(it) }.singleOrNull()
        }
    }

    private fun toTask(row: ResultRow): Task = Task(
        id = row[Tasks.id], name = row[Tasks.name], url = row[Tasks.url], type = row[Tasks.type]
    )
}