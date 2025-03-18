package com.example.drone.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.*;
import org.springframework.stereotype.Component;

import com.example.drone.config.TelemetryWebSocketHandler;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Component
public class MavlinkClient {
    private final List<Integer> udpPorts = List.of(14557);
    private final Map<Integer, Integer> totalMissionItems = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> requestedMissionList = new ConcurrentHashMap<>();
    private final Map<Integer, List<Map<String, Object>>> waypointsPerPort = new ConcurrentHashMap<>();  // ✅ Store waypoints per port
    private final ExecutorService executorService = Executors.newFixedThreadPool(udpPorts.size() + 1);
    private final Map<Integer, LinkedHashMap<String, Object>> telemetryUdpDataMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastTelemetryUpdate = new ConcurrentHashMap<>(); // ✅ Store last update timestamp
    private final long TELEMETRY_TIMEOUT_MS = 5000; // ✅ If no update in 5 seconds, consider it disconnected
    private final Map<Integer, InetAddress> portToAddressMap = new ConcurrentHashMap<>();
    private boolean isPrintingActive = true;
    private final Set<Integer> activePorts = ConcurrentHashMap.newKeySet();
    private double homeLat = 35.0766961;
    private double homeLon = 129.0921085;

    // ✅ Date formatter for dynamic timestamps
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public MavlinkClient() {
        for (int port : udpPorts) {
            telemetryUdpDataMap.put(port, initializeTelemetryData());
            waypointsPerPort.put(port, new ArrayList<>()); // ✅ Initialize waypoint list
            createLogFile(port);
            createLogFile(port);
        }
    }


    private LinkedHashMap<String, Object> initializeTelemetryData() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("GCS_IP", "Unknown");
        data.put("timestamp", getCurrentTimestamp()); // ✅ Dynamic timestamp added
        data.put("systemid", "Unknown");
        data.put("alt", null);
        data.put("dist_traveled", null);
        data.put("wp_dist", null);
        data.put("dist_to_home", 0.0);
        data.put("vertical_speed", 0.0);
        data.put("ground_speed", 0.0);
        data.put("wind_vel", 0.0);
        data.put("airspeed", 0.0);
        data.put("roll", 0.0);
        data.put("pitch", 0.0);
        data.put("yaw", 0.0);
        data.put("ch3percent", null);
        data.put("ch9out", 0.0);
        data.put("ch10out", 0.0);
        data.put("ch11out", 0.0);
        data.put("ch12out", 0.0);
        data.put("battery_voltage", 0.0);
        data.put("battery_current", 0.0);
        return data;
    }

    public void startListening() {
        for (int port : udpPorts) {
            requestedMissionList.put(port, false);
            waypointsPerPort.put(port, new ArrayList<>());  // ✅ Initialize waypoints list per port
            executorService.execute(() -> startUdpListener(port));
        }
        executorService.execute(this::printAndLogTelemetryData);
    }

    private void startUdpListener(int port) {
        try {
            List<InetAddress> zeroTierIPs = getZeroTierIPs();
            if (zeroTierIPs.isEmpty()) {
                System.err.println("❌ No ZeroTier IPs found, binding to 0.0.0.0 as fallback.");
                zeroTierIPs.add(InetAddress.getByName("0.0.0.0"));
            }

            for (InetAddress zeroTierIP : zeroTierIPs) {
                new Thread(() -> {
                    try {
                        DatagramSocket udpSocket = new DatagramSocket(new InetSocketAddress(zeroTierIP, port));
                        System.out.println("✅ Listening for MAVLink messages on ZeroTier IP " + zeroTierIP + " Port: " + port);

                        UdpInputStream udpInputStream = new UdpInputStream(udpSocket);
                        MavlinkConnection mavlinkConnection = MavlinkConnection.create(udpInputStream, null);
                        activePorts.add(port);

                        while (true) {
                            MavlinkMessage<?> message = mavlinkConnection.next();
                            if (message != null) {
                                InetAddress senderAddress = udpInputStream.getSenderAddress();
                                int senderPort = udpInputStream.getSenderPort();
                                handleUdpMavlinkMessage(message, port, udpSocket, senderAddress, senderPort);
                            }
                        }
                    } catch (Exception e) {
                        activePorts.remove(port);
                        portToAddressMap.remove(port);
                        System.err.println("❌ Error in UDP Listener (Port " + port + ", IP " + zeroTierIP + "): " + e.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to start UDP listener: " + e.getMessage());
        }
    }

    private List<InetAddress> getZeroTierIPs() {
        List<InetAddress> zeroTierIPs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // Look for ZeroTier Interfaces (name contains "zt")
                if (networkInterface.getName().contains("zt")) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            zeroTierIPs.add(addr);  // Add all IPv4 addresses found
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("❌ Error getting ZeroTier IPs: " + e.getMessage());
        }
        return zeroTierIPs;
    }


    private void handleUdpMavlinkMessage(MavlinkMessage<?> message, int port, DatagramSocket udpSocket, InetAddress senderAddress, int senderPort) {

        LinkedHashMap<String, Object> telemetryData = telemetryUdpDataMap.get(port);
        telemetryData.put("GCS_IP", senderAddress.getHostAddress());
        telemetryData.put("systemid", message.getOriginSystemId());
        // ✅ Update timestamp dynamically whenever a new message is received
        telemetryData.put("timestamp", getCurrentTimestamp());

        // ✅ Mark this drone as "recently active"
        lastTelemetryUpdate.put(port, System.currentTimeMillis());

        // ✅ If it was previously removed due to timeout, restore it
        if (!activePorts.contains(port)) {
            System.out.println("🔄 Drone on port " + port + " reconnected!");
            activePorts.add(port);
        }

        if (message.getPayload() instanceof MissionCount missionCount) {
            System.out.println("✅ Received MISSION_COUNT via (" + senderAddress + " / " + port + ") = " + missionCount.count());
            totalMissionItems.put(port, missionCount.count());

            // ✅ Clear old waypoints before starting a new mission list
            waypointsPerPort.put(port, new ArrayList<>());

            requestMissionItemsUdp(senderAddress, senderPort, port, udpSocket);
        }

        if (message.getPayload() instanceof MissionItemInt missionItemInt) {
            saveMissionItem(port, missionItemInt);  // ✅ Store waypoint

            System.out.println("✅ [UdpPort " + port + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");

            // ✅ Check if all waypoints are received
            if (waypointsPerPort.get(port).size() == totalMissionItems.getOrDefault(port, 0)) {
                sendAllWaypoints(port);
            }
        }



//        if (message.getPayload() instanceof MissionCount missionCount) {
//            System.out.println("✅ Received MISSION_COUNT via ( " + senderAddress + " / " + port + ") =  " + missionCount.count());
//            totalMissionItems.put(port, missionCount.count());
//            requestMissionItemsUdp(senderAddress, senderPort, port, udpSocket);
//        }
//
//        if (message.getPayload() instanceof MissionItemInt missionItemInt) {
//            saveMissionItem(port, missionItemInt);  // ✅ Store waypoint
//            System.out.println("✅ [UdpPort " + port + "] Received Mission Item: Seq " + missionItemInt.seq() +
//                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
//        }

        if (message.getPayload() instanceof GlobalPositionInt globalPositionInt) {
            double currentLat = globalPositionInt.lat() / 1e7;
            double currentLon = globalPositionInt.lon() / 1e7;
            double currentAlt = globalPositionInt.relativeAlt() / 1000.0;
            double distToHome = calculateDistance(currentLat, currentLon, homeLat, homeLon) * 1000.00;
            telemetryData.put("dist_to_home", distToHome);
            telemetryData.put("lat", currentLat);
            telemetryData.put("lon", currentLon);
            telemetryData.put("alt", currentAlt);
        } else if (message.getPayload() instanceof VfrHud vfrHud) {
            telemetryData.put("airspeed", vfrHud.airspeed());
            telemetryData.put("ground_speed", vfrHud.groundspeed());
            telemetryData.put("vertical_speed", vfrHud.climb());
        } else if (message.getPayload() instanceof  NavControllerOutput navControllerOutput) {
            telemetryData.put("wp_dist", navControllerOutput.wpDist());

        } else if (message.getPayload() instanceof Attitude attitude) {
            telemetryData.put("roll", String.format("%.2f", Math.toDegrees(attitude.roll())));
            telemetryData.put("pitch", String.format("%.2f", Math.toDegrees(attitude.pitch())));
            telemetryData.put("yaw", String.format("%.2f", Math.toDegrees(attitude.yaw())));
        } else if (message.getPayload() instanceof SysStatus sysStatus) {
            telemetryData.put("battery_voltage", sysStatus.voltageBattery());
            telemetryData.put("battery_current", sysStatus.currentBattery());
        } else if (message.getPayload() instanceof ServoOutputRaw servoOutputRaw) {
            telemetryData.put("ch3out", servoOutputRaw.servo3Raw());
            telemetryData.put("ch3percent", String.format("%.2f", ((servoOutputRaw.servo3Raw() - 1000.0) / 1000.0) * 100));
            telemetryData.put("ch9out", servoOutputRaw.servo9Raw());
            telemetryData.put("ch10out", servoOutputRaw.servo10Raw());
            telemetryData.put("ch11out", servoOutputRaw.servo11Raw());
            telemetryData.put("ch12out", servoOutputRaw.servo12Raw());
        } else if (message.getPayload() instanceof Wind wind) {
            telemetryData.put("wind_vel", wind.speed());
        }

        if (!requestedMissionList.get(port)) {
            requestMissionListUdp(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }


    }

    private void sendAllWaypoints(int port) {
        List<Map<String, Object>> waypoints = waypointsPerPort.get(port);
        if (waypoints == null || waypoints.isEmpty()) return;

        Map<String, Object> missionData = new LinkedHashMap<>();
        missionData.put("GCS_IP", telemetryUdpDataMap.get(port).get("GCS_IP"));
        missionData.put("udp_port", port);
        missionData.put("waypoints", waypoints);

        System.out.println("This is way point"+waypoints);
        // ✅ Send all waypoints at once via WebSocket
        TelemetryWebSocketHandler.sendMissionData(Collections.singletonList(missionData));
    }

    private void saveMissionItem(int port, MissionItemInt missionItemInt) {
        Map<String, Object> waypoint = new LinkedHashMap<>();
        waypoint.put("seq", missionItemInt.seq());
        waypoint.put("lat", missionItemInt.x() / 1e7);
        waypoint.put("lon", missionItemInt.y() / 1e7);
        waypoint.put("alt", missionItemInt.z());

        waypointsPerPort.get(port).add(waypoint);  // ✅ Add waypoint to list for this port
    }


    private void requestMissionListUdp(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
            connection.send1(255, 0, MissionRequestList.builder().targetSystem(1).targetComponent(1).build());
            DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
            udpSocket.send(packet);
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission List on port " + udpPort + ": " + e.getMessage());
        }
    }

    private void requestMissionItemsUdp(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        int missionCount = totalMissionItems.getOrDefault(udpPort, -1);
        if (missionCount <= 0) return;

        try {
            for (int i = 0; i < missionCount; i++) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
                connection.send1(255, 0, MissionRequestInt.builder().targetSystem(1).targetComponent(1).seq(i).build());
                DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
                udpSocket.send(packet);
                Thread.sleep(200);
            }
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission Items on port " + udpPort + ": " + e.getMessage());
        }
    }

    private void printTelemetryDataWithDelay() {
        while (isPrintingActive) {
            try {
                Thread.sleep(1000); // Print every 5 seconds
                printTelemetryData();
                logAllTelemetryData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("❌ Printing thread interrupted.");
            }
        }
    }

    private void logAllTelemetryData(){
        for(int port: activePorts){
            logTelemetryData(port, telemetryUdpDataMap.get(port));
        }

    }

    private void printTelemetryData() {
        if (activePorts.isEmpty()) {
            System.out.println("❌ No active MAVLink connections.");
            return;
        }

        List<String> telemetryKeys = new ArrayList<>(telemetryUdpDataMap.values().iterator().next().keySet());

        // Print Header Row
        System.out.printf("%-25s", "Telemetry Data");
        for (int port : activePorts) {
            System.out.printf("| %-15s ", "Port " + port);
        }
        System.out.println();
        System.out.println("--------------------------------------------------------------------------------------");

        // Print Each Row (Hide inactive ports)
        for (String key : telemetryKeys) {
            boolean hasNonNullValue = false;
            for (int port : activePorts) {
                Object value = telemetryUdpDataMap.get(port).getOrDefault(key, "N/A");
                if (value != null && !value.equals("N/A")) {
                    hasNonNullValue = true;
                    break;
                }
            }
            if (hasNonNullValue) {
                System.out.printf("%-25s", key);
                for (int port : activePorts) {
                    Object value = telemetryUdpDataMap.get(port).getOrDefault(key, "N/A");
                    System.out.printf("| %-15s ", value);
                }
                System.out.println();
            }
        }
        System.out.println("======================================================================================");
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371; // Radius of the earth in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c; // returns the distance in km
    }

    private final Map<Integer, File> logFiles = new ConcurrentHashMap<>();

    private void createLogFile(int port) {
        File logDir = new File("logs");
        if (!logDir.exists()) logDir.mkdir();

        File logFile = new File(logDir, "telemetry_" + port + ".log");
        try {
            if (logFile.createNewFile()) {
                System.out.println("✅ Log file created: " + logFile.getAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("❌ Error creating log file for port " + port + ": " + e.getMessage());
        }
    }

    private void logTelemetryData(int port, Map<String, Object> telemetryData) {
        File logFile = new File("logs/telemetry_" + port + ".log");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write("[" + getCurrentTimestamp() + "] " + telemetryData);
            writer.newLine();
            writer.flush(); // ✅ Ensures real-time logging
        } catch (IOException e) {
            System.err.println("❌ Error writing to log file for port " + port + ": " + e.getMessage());
        }
    }

    private void printAndLogTelemetryData() {
        while (isPrintingActive) {
            try {
                Thread.sleep(1000); // ✅ Send every second

                List<Map<String, Object>> telemetryList = new ArrayList<>();

                for (int port : activePorts) {
                    Map<String, Object> telemetryData = telemetryUdpDataMap.get(port);
                    if (!"Unknown".equals(telemetryData.get("GCS_IP"))) {  // ✅ Only send active drones
                        logTelemetryData(port, telemetryData); // ✅ Save to log

                        // ✅ Add waypoints to telemetry data
                        List<Map<String, Object>> waypoints = waypointsPerPort.getOrDefault(port, new ArrayList<>());
                        telemetryData.put("waypoints", waypoints);

                        telemetryList.add(telemetryData);
                    }
                }

                if (!telemetryList.isEmpty()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("drones", telemetryList);
                    TelemetryWebSocketHandler.sendTelemetryData(payload);  // ✅ Send telemetry with waypoints
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("❌ Printing thread interrupted.");
            }
        }
    }



    // ✅ Function to get the current timestamp dynamically
    private String getCurrentTimestamp() {
        return timestampFormat.format(new Date());
    }

    private static class UdpInputStream extends InputStream {
        private final DatagramSocket socket;
        private final byte[] buffer = new byte[4096];
        private int position = 0, length = 0;
        private InetAddress senderAddress;
        private int senderPort;

        public UdpInputStream(DatagramSocket socket) {
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            if (position >= length) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                length = packet.getLength();
                position = 0;
                senderAddress = packet.getAddress();
                senderPort = packet.getPort();
            }
            return buffer[position++] & 0xFF;
        }

        public InetAddress getSenderAddress() {
            return senderAddress;
        }

        public int getSenderPort() {
            return senderPort;
        }
    }
}