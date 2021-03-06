import HDFS.hdfs;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

import static java.lang.Integer.max;

public class Namenode implements Namenodedef {

    private HashMap<Integer, String> map_handle_filename;
    private static HashMap<String, ArrayList<Integer>> map_filename_blocks;
    private static HashMap<Integer, ArrayList<Integer>> map_block_datanode;
    private static int block_number;
    private int file_number;
    private static String[] datanode_ip = {"10.1.39.74", "10.1.39.119"};
    private static int[] datanode_port = {1099, 1099};

    private Namenode() {
        file_number = 0;
        map_handle_filename = new HashMap<>();
    }

    public byte[] openFile(byte[] inp) throws RemoteException {
        hdfs.OpenFileResponse.Builder response = hdfs.OpenFileResponse.newBuilder().setStatus(1);
        try {
            hdfs.OpenFileRequest request = hdfs.OpenFileRequest.parseFrom(inp);
            String filename = request.getFileName();
            boolean forRead = request.getForRead();
            if (forRead) {
                ArrayList<Integer> blocks = map_filename_blocks.get(filename);
                System.err.println(map_filename_blocks);
                response.addAllBlockNums(blocks);
            } else {
                file_number++; // Consider this to be a new file
                map_handle_filename.put(file_number, filename);
                response.setHandle(file_number);
            }
            return response.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] closeFile(byte[] inp) throws RemoteException {
        try {
            System.err.println("Got close File request");
            hdfs.CloseFileRequest request = hdfs.CloseFileRequest.parseFrom(inp);
            int handle = request.getHandle();
            String filename = map_handle_filename.get(handle);
            ArrayList<Integer> blocks = map_filename_blocks.get(filename);
            File file_list = new File("file_list.txt");
            if (!file_list.exists()) {
                file_list.createNewFile();
            }
            FileWriter writer = new FileWriter(file_list.getName(), true);
            BufferedWriter out = new BufferedWriter(writer);
            out.write(filename + " ");

            for (int block : blocks) {
                out.write(Integer.toString(block) + " ");
            }
            out.newLine();
            out.close();
            return hdfs.CloseFileResponse.newBuilder().setStatus(1).build().toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] getBlockLocations(byte[] inp) throws RemoteException {
        try {
            hdfs.BlockLocationRequest request = hdfs.BlockLocationRequest.parseFrom(inp);
            hdfs.BlockLocationResponse.Builder response = hdfs.BlockLocationResponse.newBuilder().setStatus(1);
            List<Integer> blocks = request.getBlockNumsList();
            for (Integer block : blocks) {
                int curBlock = block;
                hdfs.BlockLocations.Builder blockLoc = hdfs.BlockLocations.newBuilder();
                blockLoc.setBlockNumber(curBlock);
                ArrayList<Integer> datanodes = map_block_datanode.get(curBlock);
                for (Integer datanode : datanodes) {
                    hdfs.DataNodeLocation.Builder dataNodeLoc = hdfs.DataNodeLocation.newBuilder();
                    dataNodeLoc.setIp(datanode_ip[datanode]);
                    dataNodeLoc.setPort(datanode_port[datanode]);
                    blockLoc.addLocations(dataNodeLoc.build());
                }
                response.addBlockLocations(blockLoc.build());
            }
            return response.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] assignBlock(byte[] inp) throws RemoteException {
        try {
            hdfs.AssignBlockRequest request = hdfs.AssignBlockRequest.parseFrom(inp);
            int handle = request.getHandle();
            String filename = map_handle_filename.get(handle);
            hdfs.AssignBlockResponse.Builder response = hdfs.AssignBlockResponse.newBuilder().setStatus(1);
            block_number += 1;

            Random generator = new Random();
            int datanode_num = datanode_ip.length;
            int DataNode1 = generator.nextInt(datanode_num);
            int DataNode2 = generator.nextInt(datanode_num);
            while (DataNode2 == DataNode1) {
                DataNode2 = generator.nextInt(datanode_num);
            }

            ArrayList<Integer> blocks = new ArrayList<>();
            if (map_filename_blocks.containsKey(filename)) {
                blocks = map_filename_blocks.get(filename);
            }
            blocks.add(block_number);
            map_filename_blocks.put(filename, blocks);
            ArrayList<Integer> datanodes = new ArrayList<>();
            datanodes.add(DataNode1);
            datanodes.add(DataNode2);
            map_block_datanode.put(block_number, datanodes);

            hdfs.DataNodeLocation.Builder dataNode1 = hdfs.DataNodeLocation.newBuilder();
            hdfs.DataNodeLocation.Builder dataNode2 = hdfs.DataNodeLocation.newBuilder();
            dataNode1.setIp(datanode_ip[DataNode1]);
            dataNode2.setIp(datanode_ip[DataNode2]);
            dataNode1.setPort(datanode_port[DataNode1]);
            dataNode2.setPort(datanode_port[DataNode1]);

            hdfs.BlockLocations.Builder blockLoc = hdfs.BlockLocations.newBuilder();
            blockLoc.setBlockNumber(block_number);
            blockLoc.addLocations(dataNode1.build());
            blockLoc.addLocations(dataNode2.build());

            response.setNewBlock(blockLoc.build());
            return response.build().toByteArray();

        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] list(byte[] inp) throws RemoteException {
        try {
            hdfs.ListFilesResponse.Builder response = hdfs.ListFilesResponse.newBuilder().setStatus(1);
            BufferedReader in = new BufferedReader(new FileReader("file_list.txt"));
            String str;
            while ((str = in.readLine()) != null) {
                response.addFileNames(str);
            }
            return response.build().toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] blockReport(byte[] inp) throws RemoteException {
        try {
            /* Need to Improve this as well */
            hdfs.BlockReportRequest request = hdfs.BlockReportRequest.parseFrom(inp);
            int datanode_id = request.getId();
            for (int blocknum : request.getBlockNumbersList()) {
                if (map_block_datanode.get(blocknum) == null) {
                    map_block_datanode.put(blocknum, new ArrayList<>(Arrays.asList(datanode_id)));
                } else {
                    if (!map_block_datanode.get(blocknum).contains(datanode_id)) {
                        map_block_datanode.get(blocknum).add(datanode_id);
                    }
                }
            }
            System.err.println("Got Block Report from " + datanode_id);
            /* Need to Some thing here */
            hdfs.BlockReportResponse.Builder response = hdfs.BlockReportResponse.newBuilder().addStatus(1);
            return response.build().toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public byte[] heartBeat(byte[] inp) throws RemoteException {
        /* Need to acknowledge for heartbeat sent */
        try {
            hdfs.HeartBeatRequest request = hdfs.HeartBeatRequest.parseFrom(inp);
            int datanode_id = request.getId();
            System.err.println("Got Heart Beat from " + datanode_id); /* Need to do something as well */
            hdfs.HeartBeatResponse.Builder response = hdfs.HeartBeatResponse.newBuilder().setStatus(1);
            return response.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String getMyIp() {
        try {
            BufferedReader in = new BufferedReader(new FileReader("../config/namenode_ip"));
            String[] str = in.readLine().split(" ");
            return str[0];
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private static void setDatanodeIps() {
        try {
            BufferedReader in = new BufferedReader(new FileReader("../config/datanode_ips"));
            String str;
            List<String> list = new ArrayList<>();
            List<Integer> ports = new ArrayList<>();
            while ((str = in.readLine()) != null) {
                list.add(str.split(" ")[0]);
                ports.add(Integer.valueOf(str.split(" ")[1]));
            }
            int[] ret = new int[ports.size()];
            Iterator<Integer> iterator = ports.iterator();
            for (int i = 0; i < ret.length; i++) {
                ret[i] = iterator.next();
            }
            datanode_ip = list.toArray(new String[0]);
            datanode_port = ret;
        } catch (IOException e) {
            System.err.println("Cannot get Datanode Ip's");
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static void restoreFilelist() {
        File file_list = new File("file_list.txt"); /* To persist the data */
        try {
            file_list.createNewFile();
            BufferedReader reader = new BufferedReader(new FileReader(file_list));
            String line, file_name;
            while ((line = reader.readLine()) != null) {
                String[] data = line.split(" ");
                file_name = data[0];
                ArrayList<Integer> blocks_data = new ArrayList<>();
                for (int i = 1; i < data.length; i++) {
                    int cur = Integer.valueOf(data[i]);
                    blocks_data.add(cur);
                    block_number = max(cur, block_number); /* Get the Block Number Used so Far */
                }
                map_filename_blocks.put(file_name, blocks_data);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        map_filename_blocks = new HashMap<>();
        block_number = 0;
        map_block_datanode = new HashMap<>();
        /* Write the existing data */
        restoreFilelist();
        setDatanodeIps();
        String myip = getMyIp();
        if (myip.equals("")) {
            System.err.println("Error in Getting My ip");
            System.exit(-1);
        }
        System.setProperty("java.rmi.server.hostname", myip);
        try {
            Namenode obj = new Namenode();
            Namenodedef stub = (Namenodedef) UnicastRemoteObject.exportObject(obj, 0);
            Registry reg = LocateRegistry.getRegistry("0.0.0.0", 1099);
            reg.rebind("NameNode", stub);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
