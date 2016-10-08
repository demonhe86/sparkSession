package me.simon.sparkProject.spark.session;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Optional;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.simon.sparkProject.constant.Constants;
import me.simon.sparkProject.dao.*;
import me.simon.sparkProject.dao.factory.DAOFactory;
import me.simon.sparkProject.domain.*;
import me.simon.sparkProject.util.*;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.util.*;

public class UserVisitSessionAnalyzeSpark {
    public static void main(String[] args) {

        SparkConf conf = new SparkConf()
                .setAppName(Constants.SPARK_APP_NAME_SESSION)
                .set("spark.storage.memoryFraction", "0.5")             // 把cache用的JVM内存调到50%
                .set("spark.shuffle.consolidateFiles", "true")          // map端输出文件合并
                .set("spark.shuffle.file.buffer", "64")                 // shuffle map端把缓存大小设置为64kb,默认为32kb
                .set("spark.shuffle.memoryFraction", "0.3")             // shuffle reduce端把可以用来聚合内存的占比为30%
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .registerKryoClasses(new Class[]{
                        CategorySortKey.class,
                        IntList.class
                });
        SparkUtils.setMaster(conf);

        /**
         * 比如，获取top10热门品类功能中，二次排序，自定义了一个Key
         * 那个key是需要在进行shuffle的时候，进行网络传输的，因此也是要求实现序列化的
         * 启用Kryo机制以后，就会用Kryo去序列化和反序列化CategorySortKey
         */


        JavaSparkContext sc = new JavaSparkContext(conf);
        SQLContext sqlContext = SparkUtils.getSQLContext(sc.sc());

        // 生成模拟测试数据
        SparkUtils.mockData(sc, sqlContext);

        // 创建需要使用的DAO组件
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();


        // 那么首先得查询出指定的任务, 并获取任务的查询参数
        long taskid = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_TASKID_SESSION);
        Task task = taskDAO.findById(taskid);

        if(task == null) {
            System.out.println(new Date() + ": cannot find this task with id [" + taskid + "].");
            return;
        }

        JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());

        // 如果要进行session粒度的数据聚合，
        // 首先要从user_visit_action表中，查询出来指定日期范围的行为数据
        // 如果要根据用户在创建的指定参数，进行数据过滤和筛选
        /**
         * actionRDD，就是一个公共RDD
         * 第一，要用actionRDD，获取到一个公共的sessid为key的pairRDD
         * 第二，actionRDD，用到了session聚合环节里
         *
         * sessionid为key的PairRDD，是确定了，在后面要多次使用的
         *  1、与通过筛选的sessionid进行join，获取通过筛选的session的明细数据
         *  2、将这个RDD，直接传入aggregateBySession方法，进行session聚合统计
         *
         * 重构完以后，actionRDD，就只在最开始，使用一次，用来生成以sessionid为key的RDD
         */
        JavaRDD<Row> actionRDD = SparkUtils.getActionRDDByDateRange(sqlContext, taskParam);

        JavaPairRDD<String, Row> sessionid2ActionRDD = SparkUtils.getSessionid2ActionRDD(actionRDD);

        // 首先，可以将行为数据，按照session_id进行groupByKey分组
        // 此时的数据的粒度就是session粒度的，然后呢，可以将session粒度的数据
        // 与用户信息数据，进行join
        // 然后就可以获取session粒度的数据，同时呢，数据里面还包含了session对应的user信息
        // 到这里为止，获取的数据是<sessionid,(sessionid,searchKeywords,clickCategoryIds,age,professional,city,sex)>
        JavaPairRDD<String, String> sessionid2AggrInfoRDD = aggregateBySession(sqlContext, sessionid2ActionRDD);


        /**
         * 持久化，就是对RDD调用persist（）方法，并传入一个持久化级别
         * 如果是StorageLevel.MEMORY_ONLY()，纯内存，无序列化，那么用cache（）方法来替代
         * StorageLevel.MEMORY_ONLY_SER()，第二选择    内存序列化
         * StorageLevel.MEMORY_AND_DISK()，第三选择    内存+磁盘 无序列化
         * StorageLevel.MEMORY_AND_DISK_SER()，第四选择  内存+磁盘+序列化
         * StorageLevel.DISK_ONLY()，第五选择   磁盘
         *
         *
         * 如果内存充足，要使用双副本高可靠机制
         * 选择后缀带_2的策略
         * StorageLevel.MEMORY_ONLY_2()
         */
        sessionid2ActionRDD = sessionid2ActionRDD.persist(StorageLevel.MEMORY_ONLY());



        // 接着，就要针对session粒度的聚合数据，按照使用者指定的筛选参数进行数据过滤
        // 匿名内部类（算子函数），访问外部对象，是要给外部对象使用final修饰的
        Accumulator<String> sessionAggrStatAccumulator = sc.accumulator(
                "", new SessionAggrStatAccumulator());

        JavaPairRDD<String, String> filteredSessionid2AgrInfoRDD =
                filterSessionAndAggrStat(sessionid2AggrInfoRDD, taskParam,
                        sessionAggrStatAccumulator);
        /**
         * 持久化
         */
        filteredSessionid2AgrInfoRDD = filteredSessionid2AgrInfoRDD.persist(StorageLevel.MEMORY_ONLY());



        JavaPairRDD<String, Row> sessionid2detailRDD = getSessionid2detailRDD(
                filteredSessionid2AgrInfoRDD, sessionid2ActionRDD);
        /**
         * 持久化
         */
        sessionid2detailRDD = sessionid2detailRDD.persist(StorageLevel.MEMORY_ONLY());

        /**
         * 对于Accumulator这种分布式累加计算的变量的使用，有一个重要说明
         *
         * 从Accumulator中，获取数据，插入数据库的时候，是在有某一个action操作以后
         * 再进行。
         *
         * 如果没有action的话，那么整个程序根本不会运行。。。
         *
         * 是不是在calculateAndPersisitAggrStat方法之后，运行一个action操作，比如count、take
         * 不对！
         *
         * 必须把能够触发job执行的操作，放在最终写入MySQL方法之前
         *
         * 计算出来的结果，在J2EE中，是怎么显示的，是用两张柱状图显示
         */

        randomExtractSession(sc, task.getTaskid(), filteredSessionid2AgrInfoRDD, sessionid2ActionRDD);



        // 计算出各个范围的session占比，并写入MySQL
        calculateAndPersistAggrStat(sessionAggrStatAccumulator.value(), task.getTaskid());



        // 获取top10热门品类
        List<Tuple2<CategorySortKey, String>> top10CategoryList = getTop10Category(task.getTaskid(), sessionid2detailRDD);


        // 获取top10活跃session
        getTop10Session(sc, task.getTaskid(), top10CategoryList, sessionid2detailRDD);


        sc.close();

    }




    /**
     * 对行为数据按session粒度进行聚合
     * @param sessionid2ActionRDD
     * @return session粒度聚合数据
     */
    private static JavaPairRDD<String, String> aggregateBySession(SQLContext sqlContext, JavaPairRDD<String, Row> sessionid2ActionRDD) {


        // 对行为数据按session粒度进行分组
        JavaPairRDD<String, Iterable<Row>> sessionid2ActionsRDD = sessionid2ActionRDD.groupByKey();

        // 对每一个session分组进行聚合，将session中所有的搜索词和点击品类都聚合起来
        // 到此为止，获取的数据格式，如下：<userid,partAggrInfo(sessionid,searchKeywords,clickCategoryIds)>
        JavaPairRDD<Long, String> userid2PartAggrInfoRDD = sessionid2ActionsRDD.mapToPair(
                new PairFunction<Tuple2<String,Iterable<Row>>, Long, String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> tuple) throws Exception {
                        String sessionid = tuple._1;
                        Iterator<Row> iterator = tuple._2.iterator();

                        StringBuffer searchKeywordsBuffer = new StringBuffer("");
                        StringBuffer clickCategoryIdsBuffer = new StringBuffer("");

                        Long userid = null;

                        // session的起始结束时间
                        Date startTime = null;
                        Date endTime = null;
                        // session的步长
                        int stepLength = 0;

                        // 遍历session所有的访问行为
                        while(iterator.hasNext()) {
                            // 提起每个访问行为的搜索词字段和点击品类字段
                            Row row = iterator.next();

                            if(userid == null) {
                                userid = row.getLong(1);
                            }

                            String searchKeyWord = row.getString(5);
                            Long clickCatagoryId = row.getLong(6);


                            // 决定是否将搜索词或点击品类id拼接到字符串中去
                            // 首先要满足：不能是null值
                            // 其次，之前的字符串中还没有搜索词或者点击品类id
                            if(StringUtils.isNotEmpty(searchKeyWord)) {
                                if (!searchKeywordsBuffer.toString().contains(searchKeyWord)) {
                                    searchKeywordsBuffer.append(searchKeyWord + ",");
                                }
                            }

                            if(clickCatagoryId != null) {
                                if (!clickCategoryIdsBuffer.toString().contains((String.valueOf(clickCatagoryId)))) {
                                    clickCategoryIdsBuffer.append(clickCatagoryId + "");
                                }
                            }

                            // 计算session开始和结束时间
                            Date actionTime = DateUtils.parseTime(row.getString(4));
                            if(startTime == null) {
                                startTime = actionTime;
                            }

                            if(endTime == null) {
                                endTime = actionTime;
                            }

                            if(actionTime.before(startTime)) {
                                startTime = actionTime;
                            }

                            if(actionTime.after(endTime)) {
                                endTime = actionTime;
                            }

                            // 计算session访问步长
                            stepLength++;
                        }

                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());


                        // 计算session访问时长（秒）
                        long visitLenght = (endTime.getTime() - startTime.getTime()) / 1000;


                        // 聚合数据，用什么样的格式进行拼接？
                        // 使用key=value|key=value
                        String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionid + "|"
                                + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                                + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + "|"
                                + Constants.FIELD_VISIT_LENGTH + "=" + visitLenght + "|"
                                + Constants.FIELD_STEP_LENGTH + "=" + visitLenght + "|"
                                + Constants.FIELD_START_TIME + "=" + DateUtils.formatTime(startTime);

                        return new Tuple2<Long, String>(userid, partAggrInfo);
                    }
                });

        // 查询所有用户数据,并映射成<userid,Row>的格式
        String sql = "select * from user_info";
        JavaRDD<Row> userInfoRDD = sqlContext.sql(sql).javaRDD();
        JavaPairRDD<Long, Row> userid2InfoRDD = userInfoRDD.mapToPair(
                new PairFunction<Row, Long, Row>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<Long, Row> call(Row row) throws Exception {
                        return new Tuple2<Long, Row>(row.getLong(0), row);
                    }
                });


        // 将session粒度聚合数据，与用户信息进行join
        JavaPairRDD<Long, Tuple2<String, Row>> userid2FullInfoRDD =
                userid2PartAggrInfoRDD.join(userid2InfoRDD);

        // 对join起来的数据进行拼接，并且返回<sessionid,fullAggrInfo>格式的数据
        JavaPairRDD<String, String> sessionid2FullAggrInfoRDD = userid2FullInfoRDD.mapToPair(

                new PairFunction<Tuple2<Long,Tuple2<String,Row>>, String, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<String, String> call(
                            Tuple2<Long, Tuple2<String, Row>> tuple)
                            throws Exception {
                        String partAggrInfo = tuple._2._1;
                        Row userInfoRow = tuple._2._2;

                        String sessionid = StringUtils.getFieldFromConcatString(
                                partAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                        int age = userInfoRow.getInt(3);
                        String professional = userInfoRow.getString(4);
                        String city = userInfoRow.getString(5);
                        String sex = userInfoRow.getString(6);

                        String fullAggrInfo = partAggrInfo + "|"
                                + Constants.FIELD_AGE + "=" + age + "|"
                                + Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
                                + Constants.FIELD_CITY + "=" + city + "|"
                                + Constants.FIELD_SEX + "=" + sex;

                        return new Tuple2<String, String>(sessionid, fullAggrInfo);
                    }

                });


        return sessionid2FullAggrInfoRDD;
    }


    /**
     * 过滤session数据，并进行聚合统计
     * @param sessionid2AggrInfoRDD
     * @return
     */
    private static JavaPairRDD<String, String> filterSessionAndAggrStat(
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            final JSONObject taskParam,
            final Accumulator<String> sessionAggrStatAccumulator) {


        String startAge = ParamUtils.getParam(taskParam, Constants.PARAM_START_AGE);
        String endAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
        String professionals = ParamUtils.getParam(taskParam, Constants.PARAM_PROFESSIONALS);
        String cities = ParamUtils.getParam(taskParam, Constants.PARAM_CITIES);
        String sex = ParamUtils.getParam(taskParam, Constants.PARAM_SEX);
        String keywords = ParamUtils.getParam(taskParam, Constants.PARAM_KEYWORDS);
        String categoryIds = ParamUtils.getParam(taskParam, Constants.PARAM_CATEGORY_IDS);

        String _parameter = (startAge != null ? Constants.PARAM_START_AGE + "=" + startAge + "|" : "")
                + (endAge != null ? Constants.PARAM_END_AGE + "=" + endAge + "|" : "")
                + (professionals != null ? Constants.PARAM_PROFESSIONALS + "=" + professionals + "|" : "")
                + (cities != null ? Constants.PARAM_CITIES + "=" + cities + "|" : "")
                + (sex != null ? Constants.PARAM_SEX + "=" + sex + "|" : "")
                + (keywords != null ? Constants.PARAM_KEYWORDS + "=" + keywords + "|" : "")
                + (categoryIds != null ? Constants.PARAM_CATEGORY_IDS + "=" + categoryIds: "");

        if(_parameter.endsWith("\\|")) {
            _parameter = _parameter.substring(0, _parameter.length()-1);
        }

        final String parameter = _parameter;

        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = sessionid2AggrInfoRDD.filter(
                new Function<Tuple2<String, String>, Boolean>() {

                    private static final long serialVersionUID = 1L;
                    @Override
                    public Boolean call(Tuple2<String, String> tuple) throws Exception {
                        // 首先，从tuple中，获取聚合数据
                        String aggrInfo = tuple._2;

                        // 接着，依次按照筛选条件进行过滤

                        // 按照年龄范围进行过滤(startAge, endAge)
                        if(!ValidUtils.between(aggrInfo, Constants.FIELD_AGE, parameter, Constants.PARAM_START_AGE, Constants.PARAM_END_AGE)) {
                            return false;
                        }


                        // 按照职业范围进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_PROFESSIONAL, parameter, Constants.PARAM_PROFESSIONALS)) {
                            return false;
                        }

                        // 按照城市范围进行过滤（cities）
                        // 北京,上海,广州,深圳
                        // 成都
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_CITY,
                                parameter, Constants.PARAM_CITIES)) {
                            return false;
                        }


                        // 按照性别进行过滤
                        // 男/女
                        // 男，女
                        if(!ValidUtils.equal(aggrInfo, Constants.FIELD_SEX,
                                parameter, Constants.PARAM_SEX)) {
                            return false;
                        }

                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_SEARCH_KEYWORDS,
                                parameter, Constants.PARAM_KEYWORDS)) {
                            return false;
                        }

                        // 按照点击品类id进行过滤
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_CLICK_CATEGORY_IDS,
                                parameter, Constants.PARAM_CATEGORY_IDS)) {
                            return false;
                        }



                        // 主要走到这一步，那么就是需要计数的session
                        sessionAggrStatAccumulator.add(Constants.SESSION_COUNT);


                        // 计算出session的访问时长和访问步长的范围，并进行相应的累加
                        long visitLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_VISIT_LENGTH));

                        long stepLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_STEP_LENGTH));

                        calculateVisitLength(visitLength);
                        calculateStepLength(stepLength);

                        return true;
                    }


                    /**
                     * 计算访问时长范围
                     * @param visitLength
                     */
                    private void calculateVisitLength(long visitLength) {
                        if(visitLength >=1 && visitLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1s_3s);
                        } else if(visitLength >=4 && visitLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_4s_6s);
                        } else if(visitLength >=7 && visitLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_7s_9s);
                        } else if(visitLength >=10 && visitLength <= 30) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10s_30s);
                        } else if(visitLength > 30 && visitLength <= 60) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30s_60s);
                        } else if(visitLength > 60 && visitLength <= 180) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1m_3m);
                        } else if(visitLength > 180 && visitLength <= 600) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_3m_10m);
                        } else if(visitLength > 600 && visitLength <= 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10m_30m);
                        } else if(visitLength > 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30m);
                        }
                    }

                    /**
                     * 计算访问步长范围
                     * @param stepLength
                     */
                    private void calculateStepLength(long stepLength) {
                        if(stepLength >= 1 && stepLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_1_3);
                        } else if(stepLength >= 4 && stepLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_4_6);
                        } else if(stepLength >= 7 && stepLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_7_9);
                        } else if(stepLength >= 10 && stepLength <= 30) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_10_30);
                        } else if(stepLength > 30 && stepLength <= 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_30_60);
                        } else if(stepLength > 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_60);
                        }
                    }



                });


        return filteredSessionid2AggrInfoRDD;
    }

    /**
     * 获取通过筛选条件的session的访问明细数据RDD
     * @param sessionid2aggrInfoRDD
     * @param sessionid2actionRDD
     * @return
     */
    private static JavaPairRDD<String, Row> getSessionid2detailRDD(
            JavaPairRDD<String, String> sessionid2aggrInfoRDD,
            JavaPairRDD<String, Row> sessionid2actionRDD) {
        JavaPairRDD<String, Row> sessionid2detailRDD = sessionid2aggrInfoRDD
                .join(sessionid2actionRDD)
                .mapToPair(new PairFunction<Tuple2<String,Tuple2<String,Row>>, String, Row>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<String, Row> call(
                            Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
                        return new Tuple2<String, Row>(tuple._1, tuple._2._2);
                    }

                });
        return sessionid2detailRDD;
    }

    /**
     * 随机抽取session
     * @param sessionid2AggrInfoRDD
     */
    private static void randomExtractSession(
            JavaSparkContext sc,
            final long taskid,
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            JavaPairRDD<String, Row> sessionid2ActionRDD) {
        /**
         * 第一步，计算出每天每小时的session数量, 获取<yyyy-MM-dd_HH, sessionid>格式的RDD
         */
        // 获取<yyyy-MM-dd_HH,aggrInfo>格式的RDD
        JavaPairRDD<String, String> time2sessionidRDD = sessionid2AggrInfoRDD.mapToPair(
                new PairFunction<Tuple2<String, String>, String, String>() {
                    @Override
                    public Tuple2<String, String> call(Tuple2<String, String> tuple) throws Exception {
                        String aggrInfo = tuple._2;
                        String startTime = StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_START_TIME);
                        String dataHour = DateUtils.getDateHour(startTime);

                        return new Tuple2<String, String>(dataHour, aggrInfo);
                    }
                });



        // 得到每天每小时的session数量
        Map<String, Object> countMap = time2sessionidRDD.countByKey();


        /**
         * 第二步，使用按时间比例随机抽取算法，计算出每天每小时要抽取session的索引
         */
        // 将<yyyy-MM-dd_HH,count>格式的map，转换成<yyyy-MM-dd,<HH,count>>的格式
        Map<String, Map<String, Long>> dateHourCountMap =
                new HashMap<String, Map<String, Long>>();

        for(Map.Entry<String, Object> countEntry : countMap.entrySet()) {
            String dataHour = countEntry.getKey();
            String date = dataHour.split("_")[0];
            String hour = dataHour.split("_")[1];

            long count = Long.valueOf(String.valueOf(countEntry.getValue()));
            Map<String, Long> hourCountMap = dateHourCountMap.get(date);
            if (hourCountMap == null) {
                hourCountMap = new HashMap<String, Long>();
                dateHourCountMap.put(date, hourCountMap);
            }
            hourCountMap.put(hour, count);
        }

        // 开始实现按时间比例随机抽取算法
        long extarctNumberPerDay = 100 / dateHourCountMap.size();


        /**
         * session随机抽取功能
         *
         * 用到了一个比较大的变量，随机抽取索引map
         * 之前是直接在算子里面使用了这个map，每个task都会拷贝一份map副本
         * 还是比较消耗内存和网络传输性能的
         *
         * 将map做成广播变量
         *
         */
        // 每一天每个小时要抽取的session的索引 <date, <hour, (3,4,20,102)>>
        final Map<String, Map<String, List<Integer>>> dateHourExtractMap =
                new HashMap<String, Map<String, List<Integer>>>();



        Random random = new Random();

        for(Map.Entry<String, Map<String, Long>> dateHourCountEntry : dateHourCountMap.entrySet()) {
            String date = dateHourCountEntry.getKey();
            Map<String, Long> hourCountMap = dateHourCountEntry.getValue();

            // 计算出这天的session总数
            long sessionCount = 0L;
            for(long hourCount : hourCountMap.values()) {
                sessionCount += hourCount;
            }

            Map<String, List<Integer>> hourExtractMap = dateHourExtractMap.get(date);
            if(hourExtractMap == null) {
                hourExtractMap = new HashMap<String, List<Integer>>();
                dateHourExtractMap.put(date, hourExtractMap);
            }

            // 遍历每个小时
            for(Map.Entry<String, Long> hourCountEntry : hourCountMap.entrySet()) {
                String hour = hourCountEntry.getKey();
                long count = hourCountEntry.getValue();

                // 计算每个小时的session数量，占据当天总session数量的比例，直接乘以每天要抽取的数量
                // 就可以计算出当前小时要抽取的数量

                int hourExtractNumber = (int) (((double)count / (double)sessionCount) * extarctNumberPerDay);

                if (hourExtractNumber > count) {
                    hourExtractNumber = (int) count;
                }

                // 先获取当前小时的存放随机数的List
                List<Integer> extractIndexList = hourExtractMap.get(hour);
                if(extractIndexList == null) {
                    extractIndexList = new ArrayList<Integer>();
                    hourExtractMap.put(hour, extractIndexList);
                }

                // 生成上面计算出来的数量的随机数
                for(int i = 0; i < hourExtractNumber; i++) {
                    int extarctIndex = random.nextInt((int)count);
                    while (extractIndexList.contains(extarctIndex)) {
                        extarctIndex = random.nextInt((int)count);
                    }
                    extractIndexList.add(extarctIndex);
                }

            }


        }

        /**
         * fastutil的使用，很简单，比如List<Integer>的list，对应到fastutil，就是IntList
         */
        Map<String, Map<String, IntList>> fastutilDateHourExtractMap =
                new HashMap<String, Map<String, IntList>>();

        for (Map.Entry<String, Map<String, List<Integer>>> dateHourExtractEntry : dateHourExtractMap.entrySet()) {
            String date = dateHourExtractEntry.getKey();

            Map<String, List<Integer>> hourExtractMap = dateHourExtractEntry.getValue();
            HashMap<String, IntList> fastutilHourExtractMap = new HashMap<String, IntList>();

            for (Map.Entry<String, List<Integer>> hourExtractEntry : hourExtractMap.entrySet()) {
                String hour = hourExtractEntry.getKey();
                List<Integer> extractList = hourExtractEntry.getValue();
                IntList fastutilExtractList = new IntArrayList();
                for (int i = 0; i < extractList.size(); i++) {
                    fastutilExtractList.add(extractList.get(i));
                }
                fastutilHourExtractMap.put(hour, fastutilExtractList);
            }

            fastutilDateHourExtractMap.put(date, fastutilHourExtractMap);
        }




        /**
         * 广播变量，很简单
         * 其实就是SparkContext的broadcast()方法，传入你要广播的变量，即可
         */


        final Broadcast<Map<String, Map<String, IntList>>> dateHourExtractMapBroadcast =
                sc.broadcast(fastutilDateHourExtractMap);


        /**
         * 第三步: 遍历每天每小时的session，然后根据随机索引进行抽取
         */

        // 执行groupByKey算子，得到<dateHour,(session aggrInfo)>
        JavaPairRDD<String, Iterable<String>> time2sessionsRDD = time2sessionidRDD.groupByKey();


        JavaPairRDD<String, String> extractSessionidsRDD = time2sessionsRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<String,Iterable<String>>, String, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<String, String>> call(
                            Tuple2<String, Iterable<String>> tuple)
                            throws Exception {
                        List<Tuple2<String, String>> extractSessionids =
                                new ArrayList<Tuple2<String, String>>();

                        String dateHour = tuple._1;
                        String date = dateHour.split("_")[0];
                        String hour = dateHour.split("_")[1];
                        Iterator<String> iterator = tuple._2.iterator();

                        /**
                         * 使用广播变量的时候
                         * 直接调用广播变量（Broadcast类型）的value() / getValue()
                         * 可以获取到之前封装的广播变量
                         */
                        Map<String, Map<String, IntList>> dateHourExtractMap =
                                dateHourExtractMapBroadcast.value();


                        List<Integer> extractIndexList = dateHourExtractMap.get(date).get(hour);

                        ISessionRandomExtractDAO sessionRandomExtractDAO =
                                DAOFactory.getSessionRandomExtractDAO();

                        int index = 0;
                        while(iterator.hasNext()) {
                            String sessionAggrInfo = iterator.next();

                            if(extractIndexList.contains(index)) {
                                String sessionid = StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                                // 将数据写入MySQL
                                SessionRandomExtract sessionRandomExtract = new SessionRandomExtract();
                                sessionRandomExtract.setTaskid(taskid);
                                sessionRandomExtract.setSessionid(sessionid);
                                sessionRandomExtract.setStartTime(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_START_TIME));
                                sessionRandomExtract.setSearchKeywords(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_SEARCH_KEYWORDS));
                                sessionRandomExtract.setClickCategoryIds(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_CLICK_CATEGORY_IDS));

                                sessionRandomExtractDAO.insert(sessionRandomExtract);

                                // 将sessionid加入list
                                extractSessionids.add(new Tuple2<String, String>(sessionid, sessionid));
                            }

                            index++;
                        }

                        return extractSessionids;
                    }

                });

        /**
         * 第四步：获取抽取出来的session的明细数据
         */
        JavaPairRDD<String, Tuple2<String, Row>> extractSessionDetailRDD = extractSessionidsRDD.join(sessionid2ActionRDD);
        extractSessionDetailRDD.foreach(new VoidFunction<Tuple2<String,Tuple2<String,Row>>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
                Row row = tuple._2._2;

                SessionDetail sessionDetail = new SessionDetail();
                sessionDetail.setTaskid(taskid);
                sessionDetail.setUserid(row.getLong(1));
                sessionDetail.setSessionid(row.getString(2));
                sessionDetail.setPageid(row.getLong(3));
                sessionDetail.setActionTime(row.getString(4));
                sessionDetail.setSearchKeyword(row.getString(5));
                sessionDetail.setClickCategoryId(row.getLong(6));
                sessionDetail.setClickProductId(row.getLong(7));
                sessionDetail.setOrderCategoryIds(row.getString(8));
                sessionDetail.setOrderProductIds(row.getString(9));
                sessionDetail.setPayCategoryIds(row.getString(10));
                sessionDetail.setPayProductIds(row.getString(11));

                ISessionDetailDAO sessionDetailDAO = DAOFactory.getSessionDetailDAO();
                sessionDetailDAO.insert(sessionDetail);
            }
        });
    }

    /**
     * 计算各session范围占比，并写入MySQL
     * @param value
     */
    private static void calculateAndPersistAggrStat(String value, long taskid) {
        long session_count = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.SESSION_COUNT));

        long visit_length_1s_3s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_1s_3s));
        long visit_length_4s_6s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_4s_6s));
        long visit_length_7s_9s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_7s_9s));
        long visit_length_10s_30s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_10s_30s));
        long visit_length_30s_60s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_30s_60s));
        long visit_length_1m_3m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_1m_3m));
        long visit_length_3m_10m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_3m_10m));
        long visit_length_10m_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_10m_30m));
        long visit_length_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_30m));

        long step_length_1_3 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_1_3));
        long step_length_4_6 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_4_6));
        long step_length_7_9 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_7_9));
        long step_length_10_30 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_10_30));
        long step_length_30_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_30_60));
        long step_length_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_60));

        // 计算各个访问时长和访问步长的范围
        double visit_length_1s_3s_ratio = NumberUtils.formatDouble(
                (double)visit_length_1s_3s / (double)session_count, 2);
        double visit_length_4s_6s_ratio = NumberUtils.formatDouble(
                (double)visit_length_4s_6s / (double)session_count, 2);
        double visit_length_7s_9s_ratio = NumberUtils.formatDouble(
                (double)visit_length_7s_9s / (double)session_count, 2);
        double visit_length_10s_30s_ratio = NumberUtils.formatDouble(
                (double)visit_length_10s_30s / (double)session_count, 2);
        double visit_length_30s_60s_ratio = NumberUtils.formatDouble(
                (double)visit_length_30s_60s / (double)session_count, 2);
        double visit_length_1m_3m_ratio = NumberUtils.formatDouble(
                (double)visit_length_1m_3m / (double)session_count, 2);
        double visit_length_3m_10m_ratio = NumberUtils.formatDouble(
                (double)visit_length_3m_10m / (double)session_count, 2);
        double visit_length_10m_30m_ratio = NumberUtils.formatDouble(
                (double)visit_length_10m_30m / (double)session_count, 2);
        double visit_length_30m_ratio = NumberUtils.formatDouble(
                (double)visit_length_30m / (double)session_count, 2);

        double step_length_1_3_ratio = NumberUtils.formatDouble(
                (double)step_length_1_3 / (double)session_count, 2);
        double step_length_4_6_ratio = NumberUtils.formatDouble(
                (double)step_length_4_6 / (double)session_count, 2);
        double step_length_7_9_ratio = NumberUtils.formatDouble(
                (double)step_length_7_9 / (double)session_count, 2);
        double step_length_10_30_ratio = NumberUtils.formatDouble(
                (double)step_length_10_30 / (double)session_count, 2);
        double step_length_30_60_ratio = NumberUtils.formatDouble(
                (double)step_length_30_60 / (double)session_count, 2);
        double step_length_60_ratio = NumberUtils.formatDouble(
                (double)step_length_60 / (double)session_count, 2);


        // 将统计结果封装为Domain对象
        SessionAggrStat sessionAggrStat = new SessionAggrStat();
        sessionAggrStat.setTaskid(taskid);
        sessionAggrStat.setSession_count(session_count);
        sessionAggrStat.setVisit_length_1s_3s_ratio(visit_length_1s_3s_ratio);
        sessionAggrStat.setVisit_length_4s_6s_ratio(visit_length_4s_6s_ratio);
        sessionAggrStat.setVisit_length_7s_9s_ratio(visit_length_7s_9s_ratio);
        sessionAggrStat.setVisit_length_10s_30s_ratio(visit_length_10s_30s_ratio);
        sessionAggrStat.setVisit_length_30s_60s_ratio(visit_length_30s_60s_ratio);
        sessionAggrStat.setVisit_length_1m_3m_ratio(visit_length_1m_3m_ratio);
        sessionAggrStat.setVisit_length_3m_10m_ratio(visit_length_3m_10m_ratio);
        sessionAggrStat.setVisit_length_10m_30m_ratio(visit_length_10m_30m_ratio);
        sessionAggrStat.setVisit_length_30m_ratio(visit_length_30m_ratio);
        sessionAggrStat.setStep_length_1_3_ratio(step_length_1_3_ratio);
        sessionAggrStat.setStep_length_4_6_ratio(step_length_4_6_ratio);
        sessionAggrStat.setStep_length_7_9_ratio(step_length_7_9_ratio);
        sessionAggrStat.setStep_length_10_30_ratio(step_length_10_30_ratio);
        sessionAggrStat.setStep_length_30_60_ratio(step_length_30_60_ratio);
        sessionAggrStat.setStep_length_60_ratio(step_length_60_ratio);

        // 调用对应的DAO插入统计结果
        ISessionAggrStatDAO sessionAggrStatDAO = DAOFactory.getSessionAggrStatDAO();
        sessionAggrStatDAO.insert(sessionAggrStat);
    }


    /**
     * 获取top10热门品类
     * @param sessionid2detailRDD
     */
    private static List<Tuple2<CategorySortKey, String>> getTop10Category(
            long taskid,
            JavaPairRDD<String, Row> sessionid2detailRDD) {
        /**
         * 第一步：获取符合条件的session访问过的所有品类
         */
        // 获取session访问过的所有品类id
        // 访问过：指的是，点击过、下单过、支付过的品类
        JavaPairRDD<Long, Long> categoryidRDD = sessionid2detailRDD.flatMapToPair(
                new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

                    private static final long serialVersionUID = -3598223002008548596L;

                    @Override
                    public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();

                        Long clickCategoryId = row.getLong(6);
                        if (clickCategoryId != null) {
                            list.add(new Tuple2<Long, Long>(clickCategoryId, clickCategoryId));
                        }

                        String orderCategoryIds = row.getString(8);
                        if (orderCategoryIds != null && !orderCategoryIds.equals("null") && !orderCategoryIds.equals("NULL")) {
                            String[] orderCategoryIdsSplited = orderCategoryIds.split(",");
                            for (String orderCategoryId : orderCategoryIdsSplited) {
                                list.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId),
                                        Long.valueOf(orderCategoryId)));
                            }
                        }

                        String payCategoryIds = row.getString(10);
                        if (payCategoryIds != null && !payCategoryIds.equals("null") && !payCategoryIds.equals("NULL")) {
                            String[] payCategoryIdsSplited = payCategoryIds.split(",");
                            for (String payCategoryId : payCategoryIdsSplited) {
                                list.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId),
                                        Long.valueOf(payCategoryId)));
                            }
                        }

                        return list;
                    }
                });

        /**
         * 必须要进行去重
         * 如果不去重的话，会出现重复的categoryid，排序会对重复的categoryid已经countInfo进行排序
         * 最后很可能会拿到重复的数据
         */
        categoryidRDD = categoryidRDD.distinct();

        /**
         * 第二步：计算各品类的点击、下单和支付的次数
         */

        // 访问明细中，其中三种访问行为是：点击、下单和支付
        // 分别来计算各品类点击、下单和支付的次数，可以先对访问明细数据进行过滤
        // 分别过滤出点击、下单和支付行为，然后通过map、reduceByKey等算子来进行计算

        // 计算各个品类的点击次数
        JavaPairRDD<Long, Long> clickCategoryId2CountRDD =
                getClickCategoryId2CountRDD(sessionid2detailRDD);
        // 计算各个品类的下单次数
        JavaPairRDD<Long, Long> orderCategoryId2CountRDD =
                getOrderCategoryId2CountRDD(sessionid2detailRDD);
        // 计算各个品类的支付次数
        JavaPairRDD<Long, Long> payCategoryId2CountRDD =
                getPayCategoryId2CountRDD(sessionid2detailRDD);


        /**
         * 第三步：join各品类与它的点击、下单和支付的次数
         *
         * categoryidRDD中，是包含了所有的符合条件的session，访问过的品类id
         *
         * 上面分别计算出来的三份，各品类的点击、下单和支付的次数，可能不是包含所有品类的
         * 比如，有的品类，就只是被点击过，但是没有人下单和支付
         *
         * 这里，就不能使用join操作，要使用leftOuterJoin操作，就是说，如果categoryidRDD不能
         * join到自己的某个数据，比如点击、或下单、或支付次数，那么该categoryidRDD还是要保留下来的
         * 只不过，没有join到的那个数据，就是0了
         *
         */
        JavaPairRDD<Long, String> categoryid2countRDD = joinCategoryAndData(
                categoryidRDD, clickCategoryId2CountRDD, orderCategoryId2CountRDD,
                payCategoryId2CountRDD);


        /**
         * 第四步：自定义二次排序key
         */



        /**
         * 第五步：将数据映射成<CategorySortKey,info>格式的RDD，然后进行二次排序（降序）
         */
        JavaPairRDD<CategorySortKey, String> sortKey2countRDD = categoryid2countRDD.mapToPair(

                new PairFunction<Tuple2<Long,String>, CategorySortKey, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<CategorySortKey, String> call(
                            Tuple2<Long, String> tuple) throws Exception {
                        String countInfo = tuple._2;
                        long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                                countInfo, "\\|", Constants.FIELD_CLICK_COUNT));
                        long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                                countInfo, "\\|", Constants.FIELD_ORDER_COUNT));
                        long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                                countInfo, "\\|", Constants.FIELD_PAY_COUNT));

                        CategorySortKey sortKey = new CategorySortKey(clickCount,
                                orderCount, payCount);

                        return new Tuple2<CategorySortKey, String>(sortKey, countInfo);
                    }

                });

        JavaPairRDD<CategorySortKey, String> sortedCategoryCountRDD =
                sortKey2countRDD.sortByKey(false);

        /**
         * 第六步：用take(10)取出top10热门品类，并写入MySQL
         */
        ITop10CategoryDAO top10CategoryDAO = DAOFactory.getTop10CategoryDAO();

        List<Tuple2<CategorySortKey, String>> top10CategoryList =
                sortedCategoryCountRDD.take(10);

        for(Tuple2<CategorySortKey, String> tuple: top10CategoryList) {
            String countInfo = tuple._2;
            long categoryid = Long.valueOf(StringUtils.getFieldFromConcatString(
                    countInfo, "\\|", Constants.FIELD_CATEGORY_ID));
            long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                    countInfo, "\\|", Constants.FIELD_CLICK_COUNT));
            long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                    countInfo, "\\|", Constants.FIELD_ORDER_COUNT));
            long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(
                    countInfo, "\\|", Constants.FIELD_PAY_COUNT));

            Top10Category category = new Top10Category();
            category.setTaskid(taskid);
            category.setCategoryid(categoryid);
            category.setClickCount(clickCount);
            category.setOrderCount(orderCount);
            category.setPayCount(payCount);

            top10CategoryDAO.insert(category);
        }
        return top10CategoryList;
    }


    /**
     * 获取各品类点击次数RDD
     * @param sessionid2detailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getClickCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionid2detailRDD) {

        JavaPairRDD<String, Row> clickActionRDD = sessionid2detailRDD.filter(

                new Function<Tuple2<String,Row>, Boolean>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Boolean call(Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        return row.get(6) != null ? true : false;
                    }

                });
//                .coalesce(100);



        JavaPairRDD<Long, Long> clickCategoryIdRDD = clickActionRDD.mapToPair(
                new PairFunction<Tuple2<String, Row>, Long, Long>() {
                    private static final long serialVersionUID = -1745757299160394843L;

                    @Override
                    public Tuple2<Long, Long> call(Tuple2<String, Row> tuple) throws Exception {
                        long clickCategoryId = tuple._2.getLong(6);
                        return new Tuple2<Long, Long>(clickCategoryId, 1L);
                    }
                });

        JavaPairRDD<Long, Long> clickCategoryId2CountRDD = clickCategoryIdRDD.reduceByKey(
                new Function2<Long, Long, Long>() {
                    private static final long serialVersionUID = -7206306014680834706L;

                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }
                });


        return clickCategoryId2CountRDD;
    }

    /**
     * 获取各品类的下单次数RDD
     * @param sessionid2detailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getOrderCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionid2detailRDD) {
        JavaPairRDD<String, Row> orderActionRDD = sessionid2detailRDD.filter(

                new Function<Tuple2<String,Row>, Boolean>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Boolean call(Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        return row.getString(8) != null ? true : false;
                    }

                });

        JavaPairRDD<Long, Long> orderCategoryIdRDD = orderActionRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<Long, Long>> call(
                            Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        String orderCategoryIds = row.getString(8);
                        List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
                        if (orderCategoryIds != null && !orderCategoryIds.equals("null") && !orderCategoryIds.equals("NULL")) {
                            String[] orderCategoryIdsSplited = orderCategoryIds.split(",");
                            for(String orderCategoryId : orderCategoryIdsSplited) {
                                list.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId), 1L));
                            }
                        }

                        return list;
                    }

                });

        JavaPairRDD<Long, Long> orderCategoryId2CountRDD = orderCategoryIdRDD.reduceByKey(

                new Function2<Long, Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }

                });

        return orderCategoryId2CountRDD;
    }


    /**
     * 获取各个品类的支付次数RDD
     * @param sessionid2detailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getPayCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionid2detailRDD) {
        JavaPairRDD<String, Row> payActionRDD = sessionid2detailRDD.filter(

                new Function<Tuple2<String,Row>, Boolean>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Boolean call(Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        return row.getString(10) != null ? true : false;
                    }

                });

        JavaPairRDD<Long, Long> payCategoryIdRDD = payActionRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<Long, Long>> call(
                            Tuple2<String, Row> tuple) throws Exception {
                        Row row = tuple._2;
                        String payCategoryIds = row.getString(10);
                        List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
                        if (payCategoryIds != null && !payCategoryIds.equals("null") && !payCategoryIds.equals("NULL")) {
                            String[] payCategoryIdsSplited = payCategoryIds.split(",");
                            for(String payCategoryId : payCategoryIdsSplited) {
                                list.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId), 1L));
                            }
                        }

                        return list;
                    }

                });

        JavaPairRDD<Long, Long> payCategoryId2CountRDD = payCategoryIdRDD.reduceByKey(

                new Function2<Long, Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }

                });

        return payCategoryId2CountRDD;
    }



    /**
     * 连接品类RDD与数据RDD
     * @param categoryidRDD
     * @param clickCategoryId2CountRDD
     * @param orderCategoryId2CountRDD
     * @param payCategoryId2CountRDD
     * @return
     */
    private static JavaPairRDD<Long, String> joinCategoryAndData(
            JavaPairRDD<Long, Long> categoryidRDD,              // <categoryid, categoryid> 点击过的categoryid
            JavaPairRDD<Long, Long> clickCategoryId2CountRDD,   // <clickCategoryId, count>
            JavaPairRDD<Long, Long> orderCategoryId2CountRDD,   // <orderCategoryId, count>
            JavaPairRDD<Long, Long> payCategoryId2CountRDD) {



        JavaPairRDD<Long, String> tmpMapRDD = categoryidRDD.leftOuterJoin(clickCategoryId2CountRDD).mapToPair(

                new PairFunction<Tuple2<Long, Tuple2<Long, Optional<Long>>>, Long, String>() {
                    @Override
                    public Tuple2<Long, String> call(
                            Tuple2<Long, Tuple2<Long, Optional<Long>>> tuple)
                            throws Exception {
                        long categoryid = tuple._1;
                        Optional<Long> clickCountOptional = tuple._2._2;
                        long clickCount = 0L;

                        if (clickCountOptional.isPresent()) {
                            clickCount = clickCountOptional.get();
                        }

                        String value = Constants.FIELD_CATEGORY_ID + "=" + categoryid + "|" +
                                Constants.FIELD_CLICK_COUNT + "=" + clickCount;
                        return new Tuple2<Long, String>(categoryid, value);
                    }
                });

        tmpMapRDD = tmpMapRDD.leftOuterJoin(orderCategoryId2CountRDD).mapToPair(

                new PairFunction<Tuple2<Long,Tuple2<String,Optional<Long>>>, Long, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<Long, String> call(
                            Tuple2<Long, Tuple2<String, Optional<Long>>> tuple)
                            throws Exception {
                        long categoryid = tuple._1;
                        String value = tuple._2._1;

                        Optional<Long> optional = tuple._2._2;
                        long orderCount = 0L;

                        if(optional.isPresent()) {
                            orderCount = optional.get();
                        }

                        value = value + "|" + Constants.FIELD_ORDER_COUNT + "=" + orderCount;

                        return new Tuple2<Long, String>(categoryid, value);
                    }

                });

        tmpMapRDD = tmpMapRDD.leftOuterJoin(payCategoryId2CountRDD).mapToPair(

                new PairFunction<Tuple2<Long,Tuple2<String,Optional<Long>>>, Long, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<Long, String> call(
                            Tuple2<Long, Tuple2<String, Optional<Long>>> tuple)
                            throws Exception {
                        long categoryid = tuple._1;
                        String value = tuple._2._1;

                        Optional<Long> optional = tuple._2._2;
                        long payCount = 0L;

                        if(optional.isPresent()) {
                            payCount = optional.get();
                        }

                        value = value + "|" + Constants.FIELD_PAY_COUNT + "=" + payCount;

                        return new Tuple2<Long, String>(categoryid, value);
                    }

                });

        return tmpMapRDD;
    }

    /**
     *
     */
    private static void getTop10Session(
            JavaSparkContext sc,
            final long taskid,
            List<Tuple2<CategorySortKey, String>> top10CategoryList,
            JavaPairRDD<String, Row> sessionid2detailRDD ) {
        /**
         * 第一步，将top10热门品类的id，生成一份RDD
         */
        List<Tuple2<Long, Long>> top10CategoryIdList =
                new ArrayList<Tuple2<Long, Long>>();

        for (Tuple2<CategorySortKey, String> category : top10CategoryList) {
            long categoryid = Long.valueOf(StringUtils.getFieldFromConcatString(category._2, "\\|", Constants.FIELD_CATEGORY_ID));
            top10CategoryIdList.add(new Tuple2<Long, Long>(categoryid, categoryid));
        }

        JavaPairRDD<Long, Long> top10CategoryIdRDD = sc.parallelizePairs(top10CategoryIdList);

        /**
         * 第二步：计算top10品类被各session点击的次数
         */
        JavaPairRDD<String, Iterable<Row>> sessionid2detailsRDD = sessionid2detailRDD.groupByKey();

        JavaPairRDD<Long, String> categoryid2sessionCountRDD = sessionid2detailsRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<String,Iterable<Row>>, Long, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<Long, String>> call(
                            Tuple2<String, Iterable<Row>> tuple) throws Exception {
                        String sessionid = tuple._1;
                        Iterator<Row> iterator = tuple._2.iterator();

                        Map<Long, Long> categoryCountMap = new HashMap<Long, Long>();

                        // 计算出该session，对每个品类的点击次数
                        while(iterator.hasNext()) {
                            Row row = iterator.next();

                            if(row.get(6) != null) {
                                long categoryid = row.getLong(6);

                                Long count = categoryCountMap.get(categoryid);
                                if(count == null) {
                                    count = 0L;
                                }

                                count++;

                                categoryCountMap.put(categoryid, count);
                            }
                        }

                        // 返回结果，<categoryid,"sessionid,count">格式
                        List<Tuple2<Long, String>> list = new ArrayList<Tuple2<Long, String>>();

                        for(Map.Entry<Long, Long> categoryCountEntry : categoryCountMap.entrySet()) {
                            long categoryid = categoryCountEntry.getKey();
                            long count = categoryCountEntry.getValue();
                            String value = sessionid + "," + count;
                            list.add(new Tuple2<Long, String>(categoryid, value));
                        }

                        return list;
                    }

                }) ;

        // 获取到to10热门品类，被各个session点击的次数
        JavaPairRDD<Long, String> top10CategorySessionCountRDD = top10CategoryIdRDD
                .join(categoryid2sessionCountRDD)
                .mapToPair(new PairFunction<Tuple2<Long,Tuple2<Long,String>>, Long, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<Long, String> call(
                            Tuple2<Long, Tuple2<Long, String>> tuple)
                            throws Exception {
                        return new Tuple2<Long, String>(tuple._1, tuple._2._2);
                    }

                });
        /**
         * 第三步：分组取TopN算法实现，获取每个品类的top10活跃用户
         */
        JavaPairRDD<Long, Iterable<String>> top10CategorySessionCountsRDD =
                top10CategorySessionCountRDD.groupByKey();

        JavaPairRDD<String, String> top10SessionRDD = top10CategorySessionCountsRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<Long,Iterable<String>>, String, String>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<String, String>> call(
                            Tuple2<Long, Iterable<String>> tuple)
                            throws Exception {
                        long categoryid = tuple._1;
                        Iterator<String> iterator = tuple._2.iterator();

                        // 定义取topn的排序数组
                        String[] top10Sessions = new String[10];

                        while(iterator.hasNext()) {
                            String sessionCount = iterator.next();
                            long count = Long.valueOf(sessionCount.split(",")[1]);

                            // 遍历排序数组
                            for(int i = 0; i < top10Sessions.length; i++) {
                                // 如果当前i位，没有数据，那么直接将i位数据赋值为当前sessionCount
                                if(top10Sessions[i] == null) {
                                    top10Sessions[i] = sessionCount;
                                    break;
                                } else {
                                    long _count = Long.valueOf(top10Sessions[i].split(",")[1]);

                                    // 如果sessionCount比i位的sessionCount要大
                                    if(count > _count) {
                                        // 从排序数组最后一位开始，到i位，所有数据往后挪一位
                                        for(int j = 9; j > i; j--) {
                                            top10Sessions[j] = top10Sessions[j - 1];
                                        }
                                        // 将i位赋值为sessionCount
                                        top10Sessions[i] = sessionCount;
                                        break;
                                    }

                                    // 比较小，继续外层for循环
                                }
                            }
                        }

                        // 将数据写入MySQL表
                        List<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();

                        for(String sessionCount : top10Sessions) {
                            if(sessionCount != null) {
                                String sessionid = sessionCount.split(",")[0];
                                long count = Long.valueOf(sessionCount.split(",")[1]);

                                // 将top10 session插入MySQL表
                                Top10Session top10Session = new Top10Session();
                                top10Session.setTaskid(taskid);
                                top10Session.setCategoryid(categoryid);
                                top10Session.setSessionid(sessionid);
                                top10Session.setClickCount(count);

                                ITop10SessionDAO top10SessionDAO = DAOFactory.getTop10SessionDAO();
                                top10SessionDAO.insert(top10Session);

                                // 放入list
                                list.add(new Tuple2<String, String>(sessionid, sessionid));
                            }
                        }

                        return list;
                    }

                });

        /**
         * 第四步：获取top10活跃session的明细数据，并写入MySQL
         */
        JavaPairRDD<String, Tuple2<String, Row>> sessionDetailRDD =
                top10SessionRDD.join(sessionid2detailRDD);

        /**
         * 批量插入数据库
         */
        sessionDetailRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, Tuple2<String, Row>>>>() {
            private static final long serialVersionUID = 1L;
            @Override
            public void call(Iterator<Tuple2<String, Tuple2<String, Row>>> iterator) throws Exception {
                List<SessionDetail> sessionDetails = new ArrayList<SessionDetail>();
                while (iterator.hasNext()) {
                    Tuple2<String, Tuple2<String, Row>> tuple = iterator.next();

                    Row row = tuple._2._2;

                    SessionDetail sessionDetail = new SessionDetail();
                    sessionDetail.setTaskid(taskid);
                    sessionDetail.setUserid(row.getLong(1));
                    sessionDetail.setSessionid(row.getString(2));
                    sessionDetail.setPageid(row.getLong(3));
                    sessionDetail.setActionTime(row.getString(4));
                    sessionDetail.setSearchKeyword(row.getString(5));
                    sessionDetail.setClickCategoryId(row.getLong(6));
                    sessionDetail.setClickProductId(row.getLong(7));
                    sessionDetail.setOrderCategoryIds(row.getString(8));
                    sessionDetail.setOrderProductIds(row.getString(9));
                    sessionDetail.setPayCategoryIds(row.getString(10));
                    sessionDetail.setPayProductIds(row.getString(11));
                }


                ISessionDetailDAO sessionDetailDAO = DAOFactory.getSessionDetailDAO();
                sessionDetailDAO.insertBatch(sessionDetails);
            }
        });
    }

}
