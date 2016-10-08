package me.simon.sparkProject.dao.impl;

import me.simon.sparkProject.dao.IPageSplitConvertRateDAO;
import me.simon.sparkProject.domain.PageSplitConvertRate;
import me.simon.sparkProject.jdbc.JDBCHelper;


public class PageSplitConvertRateDAOImpl implements IPageSplitConvertRateDAO {
    @Override
    public void insert(PageSplitConvertRate pageSplitConvertRate) {
        String sql = "insert into page_split_convert_rate values(?,?)";
        Object[] params = new Object[]{pageSplitConvertRate.getTaskid(),
                pageSplitConvertRate.getConvertRate()};

        JDBCHelper jdbcHelper = JDBCHelper.getInstance();
        jdbcHelper.executeUpdate(sql, params);
    }
}
