package me.simon.sparkProject.test;

import me.simon.sparkProject.conf.ConfigurationManager;

public class ConfigureationManagerTest{
    public static void main(String[] args) {
        String testKey1 = ConfigurationManager.getProperty("testKey1");
        String testKey2 = ConfigurationManager.getProperty("testKey2");

        System.out.println(testKey1);
        System.out.println(testKey2);
    }


}
