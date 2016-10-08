package me.simon.sparkProject.dao.impl;

import me.simon.sparkProject.dao.ISessionRandomExtractDAO;
import me.simon.sparkProject.domain.SessionRandomExtract;
import me.simon.sparkProject.jdbc.JDBCHelper;

public class SessionRandomExtractDAOImpl implements ISessionRandomExtractDAO {

    @Override
    public void insert(SessionRandomExtract sessionRandomExtract) {
        String sql = "insert into session_random_extract values(?,?,?,?,?)";
        Object[] params = new Object[]{
                sessionRandomExtract.getTaskid(),
                sessionRandomExtract.getSessionid(),
                sessionRandomExtract.getStartTime(),
                sessionRandomExtract.getSearchKeywords(),
                sessionRandomExtract.getClickCategoryIds()};

        JDBCHelper jdbcHelper = JDBCHelper.getInstance();
        jdbcHelper.executeUpdate(sql, params);
    }
}
