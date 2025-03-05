package com.example.drone.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.MissionCount;
import io.dronefleet.mavlink.common.MissionItemInt;
import io.dronefleet.mavlink.common.MissionRequestInt;
import io.dronefleet.mavlink.common.MissionRequestList;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class MavlinkClient {
    private final List<Integer> udpPorts = List.of( 14552, 14553, 14554);
    private final String tcpHost = "localhost";
    private final int tcpPort = 14550;
    private final LinkedHashMap<String, Object> telemetryData = new LinkedHashMap<>();
    private final LinkedHashMap<String, Object> telemetryUdpData = new LinkedHashMap<>();
    private final List<Map<String, Object>> waypoints = new ArrayList<>();  // Store waypoints
    private final List<Map<String, Object>> udpWaypoints = new ArrayList<>();  // Store waypoints
    private final ExecutorService executorService = Executors.newFixedThreadPool(udpPorts.size() + 1);
    private final Map<Integer, Boolean> requestedMissionList = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> totalMissionItems = new ConcurrentHashMap<>();
    private boolean isTcpConnected = false;
    private long lastMessageTime = System.currentTimeMillis();

    private void initializeTelemetryData() {
        telemetryData.put("sysid", null);
        telemetryData.put("alt", null);
        telemetryData.put("dist_traveled", null);
        telemetryData.put("wp_dist", null);
        telemetryData.put("dist_to_home", 0.0);
        telemetryData.put("vertical_speed", 0.0);
        telemetryData.put("ground speed", 0.0);
        telemetryData.put("wind_vel", 0.0);
        telemetryData.put("airspeed", 0.0);
        telemetryData.put("roll", 0.0);
        telemetryData.put("pitch", 0.0);
        telemetryData.put("yaw", 0.0);
        telemetryData.put("time_in_air", 0.0);
        telemetryData.put("time_to_air_min_sec", 0.0);
        telemetryData.put("gps_hdop", 0.0);
        telemetryData.put("toh", null);
        telemetryData.put("tot", null);
        telemetryData.put("battery_voltage", 0.0);
        telemetryData.put("battery_current", 0.00);
        telemetryData.put("ch3percent", null);
        telemetryData.put("ch3out", null);
        telemetryData.put("ch9out", 0.00);
        telemetryData.put("ch10out", 0.00);
        telemetryData.put("ch11out", 0.00);
        telemetryData.put("ch12out", 0.00);
        telemetryData.put("waypoints_count", 0);
        telemetryData.put("waypoints", new ArrayList<String>());
    }
    private void initializeTelemetryUdpData() {
        telemetryUdpData.put("sysid", null);
        telemetryUdpData.put("alt", null);
        telemetryUdpData.put("dist_traveled", null);
        telemetryUdpData.put("wp_dist", null);
        telemetryUdpData.put("dist_to_home", 0.0);
        telemetryUdpData.put("vertical_speed", 0.0);
        telemetryUdpData.put("ground speed", 0.0);
        telemetryUdpData.put("wind_vel", 0.0);
        telemetryUdpData.put("airspeed", 0.0);
        telemetryUdpData.put("roll", 0.0);
        telemetryUdpData.put("pitch", 0.0);
        telemetryUdpData.put("yaw", 0.0);
        telemetryUdpData.put("time_in_air", 0.0);
        telemetryUdpData.put("time_to_air_min_sec", 0.0);
        telemetryUdpData.put("gps_hdop", 0.0);
        telemetryUdpData.put("toh", null);
        telemetryUdpData.put("tot", null);
        telemetryUdpData.put("battery_voltage", 0.0);
        telemetryUdpData.put("battery_current", 0.00);
        telemetryUdpData.put("ch3percent", null);
        telemetryUdpData.put("ch3out", null);
        telemetryUdpData.put("ch9out", 0.00);
        telemetryUdpData.put("ch10out", 0.00);
        telemetryUdpData.put("ch11out", 0.00);
        telemetryUdpData.put("ch12out", 0.00);
        telemetryUdpData.put("waypoints_count", 0);
        telemetryUdpData.put("waypoints", new ArrayList<String>());
    }
    public void startListening() {
        for (int port : udpPorts) {
            requestedMissionList.put(port, false);
            executorService.execute(() -> startUdpListener(port));
        }
        executorService.execute(this::startTcpListener);
        executorService.execute(this::monitorConnection);
    }

    private void startUdpListener(int port) {
        try (DatagramSocket udpSocket = new DatagramSocket(port)) {
            System.out.println("✅ Listening for MAVLink messages on UDP port " + port);
            UdpInputStream udpInputStream = new UdpInputStream(udpSocket);
            MavlinkConnection mavlinkConnection = MavlinkConnection.create(udpInputStream, null);

            while (true) {
                MavlinkMessage<?> message = mavlinkConnection.next();
                if (message != null) {
                    lastMessageTime = System.currentTimeMillis();
                    InetAddress senderAddress = udpInputStream.getSenderAddress();
                    int senderPort = udpInputStream.getSenderPort();
                    handleUdpMavlinkMessage(message, port, udpSocket, senderAddress, senderPort);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UDP Listener (Port " + port + "): " + e.getMessage());
        }
    }

    private void startTcpListener() {
        while (true) {
            try (Socket socket = new Socket(tcpHost, tcpPort);
                 InputStream inputStream = socket.getInputStream();
                 OutputStream outputStream = socket.getOutputStream()) {

                MavlinkConnection connection = MavlinkConnection.create(inputStream, outputStream);
                System.out.println("✅ TCP Connected: " + tcpHost + ":" + tcpPort);
                isTcpConnected = true;
                requestMissionListTcp(socket);

                while (true) {
                    MavlinkMessage<?> message = connection.next();
                    if (message != null) {
                        handleTcpMavlinkMessage(message, socket, tcpPort);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ TCP Connection Lost: " + e.getMessage());
                isTcpConnected = false;
                sleep(5000);
            }
        }
    }

    private void handleTcpMavlinkMessage(MavlinkMessage<?> message, Socket socket, int tcpPort) {
        lastMessageTime = System.currentTimeMillis();
        if (message.getPayload() instanceof MissionCount missionCount) {
            System.out.println("\uD83D\uDCE1 Received MISSION_COUNT via TCP: " + missionCount.count());
            requestMissionItemsTcp(socket, missionCount.count());
        }

        if (message.getPayload() instanceof MissionItemInt missionItemInt) {
            Map<String, Object> waypoint = new LinkedHashMap<>();
            waypoint.put("mission_seq", missionItemInt.seq());
            waypoint.put("mission_lat", missionItemInt.x() / 1e7);
            waypoint.put("mission_lon", missionItemInt.y() / 1e7);
            waypoint.put("mission_alt", missionItemInt.z());
            // Add to waypoints list
            waypoints.add(waypoint);
            telemetryData.put("waypoints", waypoints);
            System.out.println("\uD83D\uDCE1 [TcpPort " + tcpPort + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }
        System.out.println("It is from tcp dict--"+telemetryData.get("waypoints"));


    }

    private void handleUdpMavlinkMessage(MavlinkMessage<?> message, int port, DatagramSocket udpSocket, InetAddress senderAddress, int senderPort) {
        String messageType = message.getPayload().getClass().getSimpleName();
//        System.out.println("📡 [Port " + port + "] Received: " + messageType + " from " + senderAddress.getHostAddress());

        if (message.getPayload() instanceof MissionCount missionCount) {
            System.out.println("✅ Received MISSION_COUNT via UDP: " + missionCount.count());
            totalMissionItems.put(port, missionCount.count());
            requestMissionItemsUdp(senderAddress, senderPort, port, udpSocket);
        }
        if (message.getPayload() instanceof MissionItemInt missionItemInt) {
            Map<String, Object> udpWaypoint = new LinkedHashMap<>();
            udpWaypoint.put("mission_seq", missionItemInt.seq());
            udpWaypoint.put("mission_lat", missionItemInt.x() / 1e7);
            udpWaypoint.put("mission_lon", missionItemInt.y() / 1e7);
            udpWaypoint.put("mission_alt", missionItemInt.z());
            // Add to waypoints list
            waypoints.add(udpWaypoint);
            telemetryUdpData.put("waypoints", waypoints);
            System.out.println("\uD83D\uDCE1 [TcpPort " + tcpPort + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }

        if (!requestedMissionList.get(port)) {
            requestMissionListUdp(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }

        System.out.println("It is from udp dict--"+telemetryData.get("waypoints"));

    }

    private void requestMissionListUdp(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
            connection.send1(255, 0, MissionRequestList.builder().targetSystem(1).targetComponent(1).build());
            DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
            udpSocket.send(packet);
//            System.out.println("📡 [Port " + udpPort + "] Mission List request sent to " + address.getHostAddress());
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
//                System.out.println("📡 [Port " + udpPort + "] Requested Mission Item " + i);
                Thread.sleep(200);
            }
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission Items on port " + udpPort + ": " + e.getMessage());
        }
    }

    private void requestMissionListTcp(Socket socket) {
        try {
            OutputStream outputStream = socket.getOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
            connection.send1(255, 0, MissionRequestList.builder().targetSystem(1).targetComponent(1).build());

            // Get remote address and port for consistency with UDP logs
            InetAddress remoteAddress = socket.getInetAddress();
            int remotePort = socket.getPort();

//            System.out.println("📡 [Port " + remotePort + "] Mission List request sent via TCP to " + remoteAddress.getHostAddress());
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission List via TCP: " + e.getMessage());
        }
    }

    private void requestMissionItemsTcp(Socket socket, int missionCount) {
        if (missionCount <= 0) return;

        try {
            OutputStream outputStream = socket.getOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);

            // Get remote address and port for consistency
            InetAddress remoteAddress = socket.getInetAddress();
            int remotePort = socket.getPort();

            for (int i = 0; i < missionCount; i++) {
                connection.send1(255, 0, MissionRequestInt.builder().targetSystem(1).targetComponent(1).seq(i).build());
//                System.out.println("📡 [Port " + remotePort + "] Requested Mission Item " + i + " via TCP to " + remoteAddress.getHostAddress());
                Thread.sleep(200);
            }
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission Items via TCP: " + e.getMessage());
        }
    }


    private void monitorConnection() {
        while (true) {
            long elapsed = System.currentTimeMillis() - lastMessageTime;
            if (elapsed > 30000) {
                System.err.println("❌ No MAVLink data received for 30 seconds. Exiting...");
                System.exit(1);
            }
            sleep(5000);
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private static class UdpInputStream extends InputStream {
        private final DatagramSocket socket;
        private final byte[] buffer = new byte[4096];
        private int position = 0, length = 0;
        private InetAddress senderAddress;
        private int senderPort;

        public UdpInputStream(DatagramSocket socket) { this.socket = socket; }

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

        public InetAddress getSenderAddress() { return senderAddress; }
        public int getSenderPort() { return senderPort; }
    }
}
