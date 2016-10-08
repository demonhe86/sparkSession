package me.simon.sparkProject.dao;

import me.simon.sparkProject.domain.SessionAggrStat;

/**
 * session聚合统计模块DAO接口
 *
 */
public interface ISessionAggrStatDAO {
    void insert(SessionAggrStat sessionAggrStat);
}
