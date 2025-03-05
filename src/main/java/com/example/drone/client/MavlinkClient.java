package com.example.drone.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.*;
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
    private Double prevLat = null, prevLon = null;
    private double totalDistance = 0.0;
    private double homeLat = 35.0766961;
    private double homeLon = 129.0921085;
    private double startTimeSeconds;
    private void initializeTelemetryData() {
        telemetryData.put("systemid", null);
        telemetryData.put("GCSIP","127.0.0.1");
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
        telemetryUdpData.put("systemid", null);
        telemetryUdpData.put("GCSIP",null);
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
                startTimeSeconds = System.currentTimeMillis();
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
        int systemid=message.getOriginSystemId();
        telemetryData.put("systemid", systemid);
        double currentTimeSeconds = System.currentTimeMillis() / 1000.0;
        double timeInAir = currentTimeSeconds - startTimeSeconds;
        telemetryData.put("time_in_air", timeInAir);
        // Format time_in_air_min_sec as minutes.seconds (like 2.35 for 2 min 35 sec)
        int minutes = (int) (timeInAir / 60);
        int seconds = (int) (timeInAir % 60);
        telemetryData.put("time_in_air_min_sec", String.format("%d.%02d", minutes, seconds));
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
            if (message.getPayload() instanceof GlobalPositionInt globalPositionInt) {
                double currentLat = globalPositionInt.lat() / 1e7;
                double currentLon = globalPositionInt.lon() / 1e7;
                double currentAlt = globalPositionInt.relativeAlt() / 1000.0;
                double distToHome = (calculateDistance(currentLat, currentLon, homeLat, homeLon)) * 1000.00;

                if (prevLat != null && prevLon != null) {
                    double distance = (calculateDistance(prevLat, prevLon, currentLat, currentLon)) * 1000.00;
                    totalDistance += distance;
                    telemetryData.put("dist_traveled", totalDistance);
                }
                prevLat = currentLat;
                prevLon = currentLon;
                telemetryData.put("dist_to_home", distToHome);
                telemetryData.put("lat", currentLat);
                telemetryData.put("lon", currentLon);
                telemetryData.put("alt", currentAlt);
            }

            else if (message.getPayload() instanceof VfrHud vfrHud) {
                telemetryData.put("airspeed", vfrHud.airspeed());
                telemetryData.put("ground speed", vfrHud.groundspeed());
                telemetryData.put("vertical_speed", vfrHud.climb());
            } else if (message.getPayload() instanceof NavControllerOutput navControllerOutput) {
                telemetryData.put("wp_dist", navControllerOutput.wpDist());
            }  else if (message.getPayload() instanceof Attitude attitude) {
                telemetryData.put("roll", Math.toDegrees(attitude.roll()));
                telemetryData.put("pitch", Math.toDegrees(attitude.pitch()));
                telemetryData.put("yaw", Math.toDegrees(attitude.yaw()));
            } else if (message.getPayload() instanceof SysStatus sysStatus) {
                telemetryData.put("battery_voltage", sysStatus.voltageBattery());
                telemetryData.put("battery_current", sysStatus.currentBattery());
            } else if (message.getPayload() instanceof Wind wind) {
                telemetryData.put("wind_vel", wind.speed());
            }
//            System.out.println("\uD83D\uDCE1 [TcpPort " + tcpPort + "] Received Mission Item: Seq " + missionItemInt.seq() +
//                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }
        System.out.println("It is from tcp dict--"+telemetryData.toString());


    }

    private void handleUdpMavlinkMessage(MavlinkMessage<?> message, int port, DatagramSocket udpSocket, InetAddress senderAddress, int senderPort) {
        int systemid=message.getOriginSystemId();
        telemetryUdpData.put("systemid", systemid);
//        System.out.println("📡 [Port " + port + "] Received: " + messageType + " from " + senderAddress.getHostAddress());
        double currentTimeSeconds = System.currentTimeMillis() / 1000.0;
        double timeInAir = currentTimeSeconds - startTimeSeconds;
        telemetryUdpData.put("time_in_air", timeInAir);
        // Format time_in_air_min_sec as minutes.seconds (like 2.35 for 2 min 35 sec)
        int minutes = (int) (timeInAir / 60);
        int seconds = (int) (timeInAir % 60);
        telemetryUdpData.put("time_in_air_min_sec", String.format("%d.%02d", minutes, seconds));
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
        if (message.getPayload()  instanceof GlobalPositionInt globalPositionInt) {
            double currentLat = globalPositionInt.lat() / 1e7;
            double currentLon = globalPositionInt.lon() / 1e7;
            double currentAlt = globalPositionInt.relativeAlt() / 1000.0;
            double distToHome = (calculateDistance(currentLat, currentLon, homeLat, homeLon)) * 1000.00;

            if (prevLat != null && prevLon != null) {
                double distance = (calculateDistance(prevLat, prevLon, currentLat, currentLon)) * 1000.00;
                totalDistance += distance;
                telemetryUdpData.put("dist_traveled", totalDistance);
            }
            prevLat = currentLat;
            prevLon = currentLon;
            telemetryUdpData.put("dist_to_home", distToHome);
            telemetryUdpData.put("lat", currentLat);
            telemetryUdpData.put("lon", currentLon);
            telemetryUdpData.put("alt", currentAlt);
        }

        else if (message.getPayload()  instanceof VfrHud vfrHud) {
            telemetryUdpData.put("airspeed", vfrHud.airspeed());
            telemetryUdpData.put("ground speed", vfrHud.groundspeed());
            telemetryUdpData.put("vertical_speed", vfrHud.climb());
        } else if (message.getPayload()  instanceof NavControllerOutput navControllerOutput) {
            telemetryUdpData.put("wp_dist", navControllerOutput.wpDist());
        }  else if (message.getPayload()  instanceof Attitude attitude) {
            telemetryUdpData.put("roll", Math.toDegrees(attitude.roll()));
            telemetryUdpData.put("pitch", Math.toDegrees(attitude.pitch()));
            telemetryUdpData.put("yaw", Math.toDegrees(attitude.yaw()));
        } else if (message.getPayload()  instanceof SysStatus sysStatus) {
            telemetryUdpData.put("battery_voltage", sysStatus.voltageBattery());
            telemetryUdpData.put("battery_current", sysStatus.currentBattery());
        } else if (message.getPayload()  instanceof Wind wind) {
            telemetryUdpData.put("wind_vel", wind.speed());
        }

        if (!requestedMissionList.get(port)) {
            requestMissionListUdp(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }
        telemetryUdpData.put("GCSIP",senderAddress.getHostAddress());
        System.out.println("It is from UDP "+port+" with ip  "+senderAddress+" data--"+telemetryUdpData.toString());

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
