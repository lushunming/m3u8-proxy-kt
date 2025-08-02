package cn.com.lushunming.service

import model.Task

class TaskService {
    val tasks: MutableList<Task> = mutableListOf()

    fun getTaskList(): MutableList<Task> {
        return tasks;
    }

    fun addTask(task: Task) {
        tasks.add(task)
    }

    fun getTaskById(id: String?): Task? {

        return tasks.find { it.id == id }
    }
}