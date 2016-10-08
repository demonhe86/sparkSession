package me.simon.sparkProject.dao;

import me.simon.sparkProject.domain.Task;


public interface ITaskDAO {

    Task findById(long taskid);
}
