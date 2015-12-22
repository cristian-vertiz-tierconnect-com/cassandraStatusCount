package coderoad;

import com.datastax.driver.core.*;
import com.datastax.driver.core.PreparedStatement;

import com.google.common.collect.Lists;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mysql.jdbc.StringUtils;
import io.netty.util.internal.StringUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.List;

public class CassandraStatusCount {

    private static String[] car = {"|", "/", "-", "\\"};
    private static String database;
    private static String cassandraHost;
    private static String dbHost;
    private static Long retries = 5L;


    public static void analyse() throws Exception {
        StringBuilder sb = new StringBuilder();


        int countFVH2 = 0;

        CassandraUtils.init(cassandraHost, "riot_main");
        System.out.println("\n\n\n");

        List<Long> fieldTypeIds = Arrays.asList(31L, 32L, 52L, 53L, 54L);

        Map<Long, String> thingMap = getThingList();

        //field_value_history2
        PreparedStatement selectFVH2 = CassandraUtils.getSession().prepare
                ("SELECT field_type_id, time, value " +
                        "FROM field_value_history2 " +
                        "WHERE thing_id = :thingId " +
                        "AND field_type_id IN :fieldList limit 1000000000");

        List<String> failedThings = new ArrayList<>();
        Map<String, Map<String, List<Long>>> thingsResult = new HashMap<String, Map<String, List<Long>>>();
        for (Map.Entry<Long, String> thing : thingMap.entrySet()) {

            BoundStatement bs = new BoundStatement(selectFVH2);
            bs.setLong("thingId", thing.getKey());
            bs.setList("fieldList", fieldTypeIds);
            Long thing_id = thing.getKey();

            Map<Long, Map<String, Object>> temp = new HashMap<Long, Map<String, Object>>();

            boolean error = true, succeed = false;
            int retriesfvh2 = 0;

            while (error && !succeed && retriesfvh2 <= retries) {

                try {
                    Iterator<Row> iterator = CassandraUtils.getSession().execute(bs).iterator();
//            for (Row row : CassandraUtils.getSession().execute(bs)) {
                    while (iterator.hasNext()) {
                        Row row = iterator.next();
                        Long field_type_id = row.getLong("field_type_id");
                        Long time = row.getDate("time").getTime();
                        String value = row.getString("value");

                        if (!temp.containsKey(time) || field_type_id == 31) {
                            Map<String, Object> valueMap = new HashMap<String, Object>();

                            valueMap.put("thingTypeFieldId", field_type_id);
                            valueMap.put("value", value);

                            temp.put(time, valueMap);
                        }

                        countFVH2++;

                        if (countFVH2 % 10000 == 0) {
                            System.out.print("\rAnalysing cassandra field_value_history2 " + car[(countFVH2 / 10000) % 4]);
                        }
                    }
                    error = false;
                    succeed = true;
                } catch (Exception e) {
                    error = true;
                    succeed = false;
                    if (retriesfvh2 == retries) {
                        if (!failedThings.contains(thing_id))
                            failedThings.add(thingMap.get(thing_id) + " | " + thing_id);
                    } else {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    }
                } finally {
                    retriesfvh2++;
                }
            }

            if(succeed){
                Map<String, List<Long>> result = analyzeThing(temp);
                if (!result.isEmpty()) {
                    thingsResult.put(thingMap.get(thing_id), result);
                }
            }
        }


        System.out.println("\rAnalysing Cassandra field_value_history2 [OK]");

        sb.append("Things with correlative duplicated Status\n");

        for (Map.Entry<String, Map<String, List<Long>>> entry : thingsResult.entrySet()) {
            sb.append(entry.getKey() + "\n");
            for (Map.Entry<String, List<Long>> entry2 : entry.getValue().entrySet()) {
                sb.append("\t" + entry2.getKey().split("\\|")[0] + "\n");
                for (Long time : entry2.getValue()) {
                    sb.append("\t\t" + new Date(time) + "\n");
                }
            }
        }

        sb.append("\n================================= Summary ================================");

        sb.append("\nTotal time series in cassandra --------------> " + countFVH2);
        sb.append("\nTotal things analysed  ----------------------> " + thingMap.entrySet().size());
        sb.append("\nTotal things with duplicated status value ---> " + thingsResult.entrySet().size());

        sb.append("\n============================== END Summary ===============================\n");

        sb.append("\n\nFailed things:\n" + failedThings.toString());

        System.out.println("\n\nFailed things:\n" + failedThings.toString());

        String fileName = "results_" + (new SimpleDateFormat("YYYYMMddhhmmss").format(new Date())) + ".txt";
        File file = new File(fileName);
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        try {
            writer.write(sb.toString());
            System.out.println("***Results have been written to file  " + fileName);
        } finally {
            if (writer != null) writer.close();
            CassandraUtils.shutdown();
        }
    }

    public static  Map<String, List<Long>> analyzeThing(Map<Long, Map<String, Object>> map) {
        List<Long> times = new ArrayList<Long>(map.keySet());
        Collections.sort(times);

        String lastValue = null;
        Long lastFieldId = null;
        Long lastTime = null;
        Map<String, List<Long>> valueMap = new HashMap<String, List<Long>>();
        int chunkCount = 1;
        int countTime = 1;
        for (Long time : times) {
            String currentValue = map.get(time).get("value").toString();
            Long currentTime = time;
            Long currentFieldId = Long.parseLong(map.get(time).get("thingTypeFieldId").toString());
            if (lastValue != null &&
                    lastFieldId != null &&
                    currentValue.equals(lastValue) &&
                    currentFieldId == lastFieldId &&
                    currentFieldId == 31) {

                if (valueMap.containsKey(currentValue + "|" + chunkCount)) {
                    if (!valueMap.get(lastValue + "|" + chunkCount).contains(lastTime))
                        valueMap.get(lastValue + "|" + chunkCount).add(lastTime);

                    if (!valueMap.get(currentValue + "|" + chunkCount).contains(currentTime))
                        valueMap.get(currentValue + "|" + chunkCount).add(currentTime);
                } else {
                    List<Long> timesDup = new ArrayList<Long>();
                    timesDup.add(lastTime);
                    timesDup.add(currentTime);
                    valueMap.put(currentValue + "|" + chunkCount, timesDup);
                }
            } else
                chunkCount++;
            lastValue = currentValue;
            lastFieldId = currentFieldId;
            lastTime = currentTime;
//            if (countTime % 100 == 0) {
//                System.out.print("\rAnalysing values " + car[(countTime / 100) % 4]);
//            }
            countTime++;
        }
        return valueMap;
    }

    public static Map<Long, String> getThingList() throws SQLException, IllegalAccessException, InstantiationException, ClassNotFoundException {
        Connection conn = initMysqlJDBCDrivers();

        java.sql.ResultSet rs = null;
        if (conn != null && database.equals("mysql")) {
            rs = conn.createStatement().executeQuery("SELECT id, serial FROM apc_thing");
        } else if (conn != null && database.equals("mssql")) {
            rs = conn.createStatement().executeQuery("SELECT id, serial FROM dbo.apc_thing");
        }

        Map<Long, String> thingList = new HashMap<Long, String>();

        if (rs != null) {
            int counter = 0;
            while (rs.next()) {

                Long thingId = rs.getLong("id");
                String serial = rs.getString("serial");

                thingList.put(thingId, serial);

                counter++;
                if (counter % 10000 == 0) {
                    System.out.print("\rRetrieving data from apc_thing  " + car[(counter / 10000) % 4]);
                }
            }
            System.out.println("\rRetrieving data from apc_thing [OK]");
            conn.close();
        } else {
            System.out.println("No connection available for " + System.getProperty("connection.url." + database));
        }


        return thingList;
    }

    public static Connection initMysqlJDBCDrivers() throws ClassNotFoundException, SQLException, IllegalAccessException, InstantiationException {

        String url = System.getProperty("connection.url." + database);
        String userName = System.getProperty("connection.username." + database);
        String password = System.getProperty("connection.password." + database);

        String driverMysql = "org.gjt.mm.mysql.Driver";
        String driverMssql = "net.sourceforge.jtds.jdbc.Driver";

        Class.forName(driverMysql).newInstance();
        Class.forName(driverMssql).newInstance();
        return DriverManager.getConnection(url, userName, password);
    }

    public static void setDBPrperties() {
        System.getProperties().put("connection.url.mysql", "jdbc:mysql://" + dbHost + ":3306/riot_main");
        System.getProperties().put("connection.username.mysql", "root");
        System.getProperties().put("connection.password.mysql", "control123!");
        System.getProperties().put("connection.url.mssql", "jdbc:jtds:sqlserver://" + dbHost + ":1433/riot_main");
        System.getProperties().put("connection.username.mssql", "sa");
        System.getProperties().put("connection.password.mssql", "control123!");

    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.print("Usage java -jar cassandraStatusCount.jar <DB TYPE> <DB HOST> <CASSANDRA HOST>");
            System.out.print("( Example: java -jar cassandraStatusCount.jar mysql localhost localhost )");
            System.exit(0);
        }
        Long t1 = System.currentTimeMillis();
        Long t2 = 0L;
        try {
            database = args[0];
            dbHost = args[1];
            cassandraHost = args[2];
            setDBPrperties();
            analyse();
            t2 = System.currentTimeMillis() - t1;
            System.out.println("\nElapsed time " + t2 + " ms");
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            t2 = System.currentTimeMillis() - t1;
            System.out.println("\nElapsed time " + t2 + " ms");
            System.exit(-1);
        }
    }

}
