package me.simon.sparkProject.test;

import me.simon.sparkProject.dao.ITaskDAO;
import me.simon.sparkProject.dao.factory.DAOFactory;
import me.simon.sparkProject.domain.Task;


public class TaskDAOTest{
    public static void main(String[] args) {
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();
        Task task = taskDAO.findById(2);
        System.out.println(task.getTaskName());
    }
}
