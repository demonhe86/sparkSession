package me.simon.sparkProject.spark.session;

import me.simon.sparkProject.constant.Constants;
import me.simon.sparkProject.util.StringUtils;
import org.apache.spark.AccumulatorParam;



/**
 * session聚合统计Accumulator
 *
 *
 */
public class SessionAggrStatAccumulator implements AccumulatorParam<String>{
    private static final long serialVersionUID = 1L;

    /**
     * zero方法，其实主要用于数据的初始化
     * 那么，就返回一个值，就是初始化中，所有范围区间的数量，都是0
     * 各个范围取件的统计数量的拼接，还是采用一如既往的key=vale|key=value的连接格式
     * @param v
     * @return
     */
    @Override
    public String zero(String v) {
        return Constants.SESSION_COUNT + "=0|"
                + Constants.TIME_PERIOD_1s_3s + "=0|"
                + Constants.TIME_PERIOD_4s_6s + "=0|"
                + Constants.TIME_PERIOD_7s_9s + "=0|"
                + Constants.TIME_PERIOD_10s_30s + "=0|"
                + Constants.TIME_PERIOD_30s_60s + "=0|"
                + Constants.TIME_PERIOD_1m_3m + "=0|"
                + Constants.TIME_PERIOD_3m_10m + "=0|"
                + Constants.TIME_PERIOD_10m_30m + "=0|"
                + Constants.TIME_PERIOD_30m + "=0|"
                + Constants.STEP_PERIOD_1_3 + "=0|"
                + Constants.STEP_PERIOD_4_6 + "=0|"
                + Constants.STEP_PERIOD_7_9 + "=0|"
                + Constants.STEP_PERIOD_10_30 + "=0|"
                + Constants.STEP_PERIOD_30_60 + "=0|"
                + Constants.STEP_PERIOD_60 + "=0";
    }

    /**
     * addInPlace和addAccumulartor可以理解为是一样的
     * 这两个方法，主要是实现，v1可能是初始化的那个连接串
     * v2，就是在遍历session的时候，判断出某个session的区间，然后会用Constants.TIME_PERIOD_1s_3s
     * 在v1找到v2对应的value，累加1，然后再更新回连接串里面去
     * @param v1
     * @param v2
     * @return
     */
    @Override
    public String addInPlace(String v1, String v2) {
        return add(v1, v2);
    }


    @Override
    public String addAccumulator(String v1, String v2) {
        return add(v1, v2);
    }

    /**
     * session统计计算逻辑
     * @param v1  v1连接串
     * @param v2  v2范围统计
     * @return   更新后的连接串
     */
    private String add(String v1, String v2) {
        // 校验：v1为空的话，直接返回v2
        if (StringUtils.isEmpty(v1)) {
            return v2;
        }

        // 使用StringUtils工具类，从v1中，提取v2对应的值，并累加1
        String oldValue = StringUtils.getFieldFromConcatString(v1, "\\|", v2);
        if (oldValue != null) {
            // 将范围区间原有的值，累加1
            int newValue = Integer.valueOf(oldValue) + 1;
            // 使用StringUtils工具类，将v1中，v2对应的值，设置成新的累加后的值
            return StringUtils.setFieldInConcatString(v1, "\\|", v2, String.valueOf(newValue));
        }

        return v1;
    }

}
