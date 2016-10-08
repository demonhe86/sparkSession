package me.simon.sparkProject.dao.impl;

import me.simon.sparkProject.dao.ITop10CategoryDAO;
import me.simon.sparkProject.domain.Top10Category;
import me.simon.sparkProject.jdbc.JDBCHelper;

public class Top10CategoryDAOImpl implements ITop10CategoryDAO {
    @Override
    public void insert(Top10Category category) {
        String sql = "insert into top10_category values(?,?,?,?,?)";

        Object[] params = new Object[]{category.getTaskid(),
                category.getCategoryid(),
                category.getClickCount(),
                category.getOrderCount(),
                category.getPayCount()};

        JDBCHelper jdbcHelper = JDBCHelper.getInstance();
        jdbcHelper.executeUpdate(sql, params);
    }
}
