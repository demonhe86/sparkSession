package me.simon.sparkProject.dao.factory;

import me.simon.sparkProject.dao.*;
import me.simon.sparkProject.dao.impl.*;

/**
 * 工厂方法，用户返回类
 */
public class DAOFactory {

    public static ITaskDAO getTaskDAO() {
        return new TaskDAOImpl();
    }


    public static ISessionAggrStatDAO getSessionAggrStatDAO() {
        return new SessionAggrStatDAOImpl();
    }

    public static ISessionRandomExtractDAO getSessionRandomExtractDAO() {
        return new SessionRandomExtractDAOImpl();
    }

    public static ISessionDetailDAO getSessionDetailDAO() {
        return new SessionDetailDAOImpl();
    }

    public static ITop10CategoryDAO getTop10CategoryDAO() {
        return new Top10CategoryDAOImpl();
    }

    public static ITop10SessionDAO getTop10SessionDAO() {
        return new Top10SessionDAOImpl();
    }

    public static IPageSplitConvertRateDAO getPageSplitConvertRateDAO() {
        return new PageSplitConvertRateDAOImpl();
    }
}
