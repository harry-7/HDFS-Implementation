import HDFS.hdfs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TaskTracker {
    private static Jobtrackerdef jobtracker_stub;
    private static int map_capacity = 2;
    private static int reduce_capacity = 2;
    private static ThreadPoolExecutor map_pool;
    private static ThreadPoolExecutor reduce_pool;
    static Namenodedef namenode_stub;
    private static HashMap<String, hdfs.MapTaskStatus> map_statuses;
    private static HashMap<String, hdfs.ReduceTaskStatus> reduce_statuses;
    private static Helper helper;
    private static Lock mapLock, reduceLock;

    static public void main(String args[]) {
        try {
            String host = "10.1.39.64";
            String namenode_host = "10.1.39.64";
            int namenode_port = 1099;
            int port = 1099;
            mapLock = new ReentrantLock();
            reduceLock = new ReentrantLock();
            try {
                BufferedReader in = new BufferedReader(new FileReader("../config/namenode_ip"));
                String[] str = in.readLine().split(" ");
                namenode_host = str[0];
                namenode_port = Integer.valueOf(str[1]);
                in = new BufferedReader(new FileReader("../config/jobtracker_ip"));
                str = in.readLine().split(" ");
                host = str[0];
                port = Integer.valueOf(str[1]);
            } catch (IOException e) {
                e.printStackTrace();
            }
            map_pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(map_capacity);
            reduce_pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(reduce_capacity);
            Registry registry = LocateRegistry.getRegistry(host, port);
            jobtracker_stub = (Jobtrackerdef) registry.lookup("JobTracker");
            Registry reg = LocateRegistry.getRegistry(namenode_host, namenode_port);
            namenode_stub = (Namenodedef) reg.lookup("NameNode");
            int id = Integer.valueOf(args[0]);
            map_statuses = new HashMap<>();
            reduce_statuses = new HashMap<>();
            helper = new Helper(namenode_stub);
            new HeartbeatHandler(id).run();
        } catch (RemoteException | NotBoundException e) {
            e.printStackTrace();
        }
    }


    private static class mapExecutor implements Runnable {
        /* Class to deal with Map Tasks */
        private byte[] info;

        mapExecutor(byte[] info) {
            this.info = info;
        }

        private String getSearchRegex(int job_id) {
            return helper.read_from_hdfs("job_" + String.valueOf(job_id) + ".xml");
        }

        public void run() {
            try {
                hdfs.MapTaskInfo map_info = hdfs.MapTaskInfo.parseFrom(info);
                hdfs.BlockLocations block_loc = map_info.getInputBlocks();
                int jobId = map_info.getJobId();
                int taskId = map_info.getTaskId();
                String out_file = "map_" + Integer.toString(jobId) + "_" + Integer.toString(taskId);
                hdfs.DataNodeLocation dnLoc = block_loc.getLocationsList().get(0);
                int blockNum = block_loc.getBlockNumber();
                Registry registry = LocateRegistry.getRegistry(dnLoc.getIp(), dnLoc.getPort());
                Datanodedef datanode_stub = (Datanodedef) registry.lookup("DataNode");
                hdfs.ReadBlockRequest.Builder read_req = hdfs.ReadBlockRequest.newBuilder();
                read_req.setBlockNumber(blockNum);
                byte[] read_resp = datanode_stub.readBlock(read_req.build().toByteArray());
                if (read_resp != null) {
                    String mapName = map_info.getMapName();
                    System.err.println(mapName);
                    String search_regex = getSearchRegex(jobId);
                    Mapper mymap = (Mapper) Class.forName(mapName).getConstructor(String.class).newInstance(search_regex);
                    hdfs.ReadBlockResponse readBlockResponse = hdfs.ReadBlockResponse.parseFrom(read_resp);
                    ByteString data = readBlockResponse.getData(0);
                    String input = new String(data.toByteArray());
                    String out_data = "";
                    Scanner scanner = new Scanner(input);
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        System.err.println("Calling map on: " + line);
                        out_data += mymap.map(line);
                    }
                    scanner.close();
                    if (helper.write_to_hdfs(out_file, out_data)) {
                    /* Set the status only when write is successfull */
                        System.out.println("MAP TASK COMPLETED, SET STATUS TO TRUE");
                        hdfs.MapTaskStatus.Builder map_stat = hdfs.MapTaskStatus.newBuilder();
                        map_stat.setJobId(map_info.getJobId());
                        map_stat.setTaskId(map_info.getTaskId());
                        map_stat.setTaskCompleted(true);
                        map_stat.setMapOutputFile(out_file);
                        mapLock.lock();
                        map_statuses.put(out_file, map_stat.build());
                        mapLock.unlock();
                    }
                }
            } catch (InvalidProtocolBufferException | ClassNotFoundException | NotBoundException | RemoteException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
    }

    private static class reducerExecutor implements Runnable {
        /* Class to deal with Reducer Tasks */
        private byte[] info;

        reducerExecutor(byte[] info) {
            this.info = info;
        }

        public void run() {
            try {
                hdfs.ReducerTaskInfo reduce_info = hdfs.ReducerTaskInfo.parseFrom(info);
                List<String> map_output_files = reduce_info.getMapOutputFilesList();
                String out_file = reduce_info.getOutputFile();
                int jobId = reduce_info.getJobId();
                int taskId = reduce_info.getTaskId();
                String idx = "reduce_" + Integer.toString(jobId) + "_" + Integer.toString(taskId);
                String out_data = "";
                for (String map_output_file : map_output_files) {
                    String reducerName = reduce_info.getReducerName();
                    System.err.println("Reducer Name:" + reducerName);
                    Reducer reducer = (Reducer) Class.forName(reducerName).newInstance();
                    String input = helper.read_from_hdfs(map_output_file);
                    Scanner scanner = new Scanner(input);
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        System.err.println("Calling Reduce on: " + line);
                        out_data += reducer.reduce(line);
                    }
                    scanner.close();
                }
                if (helper.write_to_hdfs(out_file, out_data)) {
                /* Set the status only when write is successfull */
                    hdfs.ReduceTaskStatus.Builder reduce_stat = hdfs.ReduceTaskStatus.newBuilder();
                    reduce_stat.setJobId(jobId);
                    reduce_stat.setTaskId(taskId);
                    reduce_stat.setTaskCompleted(true);
                    reduceLock.lock();
                    reduce_statuses.put(idx, reduce_stat.build());
                    reduceLock.unlock();
                }
            } catch (InvalidProtocolBufferException | ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private static class HeartbeatHandler extends Thread {
        /* Handles the heart beat */
        private int id;

        HeartbeatHandler(int node_id) {
            id = node_id;
        }

        public void run() {
            try {
                while (true) {
                    /* Send Periodically HeartBeat */
                    hdfs.HeartBeatRequestMapReduce.Builder request = hdfs.HeartBeatRequestMapReduce.newBuilder();
                    request.setTaskTrackerId(id);
                    request.setNumMapSlotsFree(map_capacity - map_pool.getActiveCount());
                    request.setNumReduceSlotsFree(reduce_capacity - reduce_pool.getActiveCount());
                    /* Need to set the status as well */
                    mapLock.lock();
                    for (HashMap.Entry<String, hdfs.MapTaskStatus> entry : map_statuses.entrySet()) {
                        String key = entry.getKey();
                        hdfs.MapTaskStatus map_status = entry.getValue();
                        request.addMapStatus(map_status);
                        if (map_status.getTaskCompleted()) {
                            map_statuses.remove(key);
                        }
                    }
                    mapLock.unlock();
                    reduceLock.lock();
                    for (HashMap.Entry<String, hdfs.ReduceTaskStatus> entry : reduce_statuses.entrySet()) {
                        String key = entry.getKey();
                        hdfs.ReduceTaskStatus reduce_status = entry.getValue();
                        request.addReduceStatus(reduce_status);
                        if (reduce_status.getTaskCompleted()) {
                            reduce_statuses.remove(key);
                        }
                    }
                    reduceLock.unlock();
                    /* Get response and act on it */
                    byte[] resp = jobtracker_stub.heartBeat(request.build().toByteArray());
                    System.err.println("Sent HeartBeat from Task Tracker " + id);
                    hdfs.HeartBeatResponseMapReduce response = hdfs.HeartBeatResponseMapReduce.parseFrom(resp);
                    List<hdfs.MapTaskInfo> map_infos = response.getMapTasksList();
                    System.err.println("Map Infos: " + map_infos);
                    List<hdfs.ReducerTaskInfo> reduce_infos = response.getReduceTasksList();
                    System.err.println("Reduce Infos: " + reduce_infos);
                    mapLock.lock();
                    for (hdfs.MapTaskInfo map_info : map_infos) {
                        hdfs.MapTaskStatus.Builder map_stat = hdfs.MapTaskStatus.newBuilder();
                        map_stat.setJobId(map_info.getJobId());
                        map_stat.setTaskId(map_info.getTaskId());
                        map_stat.setTaskCompleted(false);
                        String out_file = "map_" + String.valueOf(map_info.getJobId()) + "_" + String.valueOf(map_info.getTaskId());
                        map_stat.setMapOutputFile(out_file);

                        map_statuses.put(out_file, map_stat.build());
                        System.err.println("Calling Thread Pool for this map task "
                                + map_info.getJobId() + "-" + map_info.getTaskId());

                        Runnable map_executor = new mapExecutor(map_info.toByteArray());
                        map_pool.execute(map_executor);
                    }
                    mapLock.unlock();
                    reduceLock.lock();
                    for (hdfs.ReducerTaskInfo reduce_info : reduce_infos) {
                        hdfs.ReduceTaskStatus.Builder reduce_stat = hdfs.ReduceTaskStatus.newBuilder();
                        reduce_stat.setJobId(reduce_info.getJobId());
                        reduce_stat.setTaskId(reduce_info.getTaskId());
                        reduce_stat.setTaskCompleted(false);
                        String idx = "reduce" + String.valueOf(reduce_info.getJobId()) + "_" +
                                String.valueOf(reduce_info.getTaskId());
                        reduce_statuses.put(idx, reduce_stat.build());
                        System.err.println("Calling Thread Pool for this reduce task "
                                + reduce_info.getJobId() + "-" + reduce_info.getTaskId());
                        Runnable reduce_executor = new reducerExecutor(reduce_info.toByteArray());
                        reduce_pool.execute(reduce_executor);
                    }
                    reduceLock.unlock();
                    Thread.sleep(10000); /* Sleep for 10 Seconds */
                }
            } catch (InterruptedException | InvalidProtocolBufferException | RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
