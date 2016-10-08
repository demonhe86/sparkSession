package me.simon.sparkProject.dao.impl;

import me.simon.sparkProject.dao.ISessionDetailDAO;
import me.simon.sparkProject.domain.SessionDetail;
import me.simon.sparkProject.jdbc.JDBCHelper;

import java.util.ArrayList;
import java.util.List;


public class SessionDetailDAOImpl implements ISessionDetailDAO {
    @Override
    public void insert(SessionDetail sessionDetail) {
        String sql = "insert into session_detail values(?,?,?,?,?,?,?,?,?,?,?,?)";
        Object[] params = new Object[]{sessionDetail.getTaskid(),
                sessionDetail.getUserid(),
                sessionDetail.getSessionid(),
                sessionDetail.getPageid(),
                sessionDetail.getActionTime(),
                sessionDetail.getSearchKeyword(),
                sessionDetail.getClickCategoryId(),
                sessionDetail.getClickProductId(),
                sessionDetail.getOrderCategoryIds(),
                sessionDetail.getOrderProductIds(),
                sessionDetail.getPayCategoryIds(),
                sessionDetail.getPayProductIds()};

        JDBCHelper jdbcHelper = JDBCHelper.getInstance();
        jdbcHelper.executeUpdate(sql, params);
    }

    @Override
    public void insertBatch(List<SessionDetail> sessionDetails) {
        String sql = "insert into session_detail values(?,?,?,?,?,?,?,?,?,?,?,?)";
        List<Object[]> paramsList = new ArrayList<Object[]>();
        for(SessionDetail sessionDetail : sessionDetails) {
            Object[] params = new Object[]{sessionDetail.getTaskid(),
                    sessionDetail.getUserid(),
                    sessionDetail.getSessionid(),
                    sessionDetail.getPageid(),
                    sessionDetail.getActionTime(),
                    sessionDetail.getSearchKeyword(),
                    sessionDetail.getClickCategoryId(),
                    sessionDetail.getClickProductId(),
                    sessionDetail.getOrderCategoryIds(),
                    sessionDetail.getOrderProductIds(),
                    sessionDetail.getPayCategoryIds(),
                    sessionDetail.getPayProductIds()};
            paramsList.add(params);
        }

        JDBCHelper jdbcHelper = JDBCHelper.getInstance();
        jdbcHelper.executeBatch(sql, paramsList);
    }
}
