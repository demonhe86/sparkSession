package me.simon.sparkProject.test;

import me.simon.sparkProject.util.DateUtils;
import me.simon.sparkProject.util.StringUtils;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;

import java.io.*;
import java.util.Random;
import java.util.UUID;


public class makeData {
    public static void main(String[] args) throws IOException {
        FileOutputStream fos=new FileOutputStream(new File("/Users/DemonHe/Desktop/user_visit_action.txt"));

        OutputStreamWriter osw=new OutputStreamWriter(fos, "UTF-8");
        BufferedWriter  bw=new BufferedWriter(osw);

        String[] searchKeywords = new String[] {"A", "B", "C", "D",
                "E", "F", "G", "H", "I", "J"};
        String date = DateUtils.getTodayDate();
        String[] actions = new String[]{"search", "click", "order", "pay"};
        Random random = new Random();

        for(int i = 0; i < 100000; i++) {
            long userid = random.nextInt(100);

            for(int j = 0; j < 10; j++) {
                String sessionid = UUID.randomUUID().toString().replace("-", "");
                String baseActionTime = date + " " + random.nextInt(23);

                Long clickCategoryId = null;

                for(int k = 0; k < random.nextInt(100); k++) {
                    long pageid = random.nextInt(10);
                    String actionTime = baseActionTime + ":" + StringUtils.fulfuill(String.valueOf(random.nextInt(59))) + ":" + StringUtils.fulfuill(String.valueOf(random.nextInt(59)));
                    String searchKeyword = null;
                    Long clickProductId = null;
                    String orderCategoryIds = null;
                    String orderProductIds = null;
                    String payCategoryIds = null;
                    String payProductIds = null;

                    String action = actions[random.nextInt(4)];
                    if("search".equals(action)) {
                        searchKeyword = searchKeywords[random.nextInt(10)];
                    } else if("click".equals(action)) {
                        if(clickCategoryId == null) {
                            clickCategoryId = Long.valueOf(String.valueOf(random.nextInt(100)));
                        }
                        clickProductId = Long.valueOf(String.valueOf(random.nextInt(100)));
                    } else if("order".equals(action)) {
                        orderCategoryIds = String.valueOf(random.nextInt(100));
                        orderProductIds = String.valueOf(random.nextInt(100));
                    } else if("pay".equals(action)) {
                        payCategoryIds = String.valueOf(random.nextInt(100));
                        payProductIds = String.valueOf(random.nextInt(100));
                    }


                    bw.write(date + "\t" + userid + "\t" + sessionid + "\t" + pageid + "\t" + actionTime + "\t" +
                            (searchKeyword == null ? "\\"+ "N" : searchKeyword) + "\t" +
                            (clickCategoryId == null ? "\\"+ "N" : clickCategoryId) + "\t" +
                            (clickProductId == null ? "\\"+ "N" : clickProductId) + "\t" +
                            (orderCategoryIds == null ? "\\"+ "N" : orderCategoryIds) + "\t" +
                            (orderProductIds == null ? "\\"+ "N" : orderProductIds) + "\t" +
                            (payCategoryIds == null ? "\\"+ "N" : payCategoryIds) + "\t" +
                            (payProductIds == null ? "\\"+ "N" : payProductIds) + "\n");
                }
            }
        }

        bw.close();
        osw.close();
        fos.close();
    }
}
