package me.simon.sparkProject.test;

import me.simon.sparkProject.jdbc.JDBCHelper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;


public class JDBCHelperTest{
    public static void main(String[] args) throws Exception {
        JDBCHelper jdbcHelper = JDBCHelper.getInstance();

        // 测试普通的增删改语句
        jdbcHelper.executeUpdate(
                "insert into test_user(name,age) values(?,?)",
                new Object[]{"王ss", 108});

//        final Map<String, Object> testUser  = new HashMap<String, Object>();

//        jdbcHelper.executeQuery(
//                "select name, age from test_user where id=?",
//                new Object[]{3},
//                new JDBCHelper.QueryCallback() {
//
//                    public void process(ResultSet rs) throws  Exception {
//                        if (rs.next()) {
//                            String name = rs.getString(1);
//                            int age = rs.getInt(2);
//
//                            testUser.put("name", name);
//                            testUser.put("age", age);
//                        }
//                    }
//                }
//        );
//        System.out.println(testUser.get("name") + ":" + testUser.get("age"));

//        String sql = "insert into test_user(name, age) values(?,?)";
//        List<Object[]> paramsList = new ArrayList<Object[]>();
//        paramsList.add(new Object[]{"码子", 30});
//        paramsList.add(new Object[]{"王五", 25});
//
//        jdbcHelper.executeBatch(sql, paramsList);

    }

}
