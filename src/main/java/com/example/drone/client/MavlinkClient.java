package com.example.drone.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Component
public class MavlinkClient {
    private final List<Integer> udpPorts = List.of( 14552, 14553, 14554);
    private final String tcpHost = "localhost";
    private final int tcpPort = 14550;
    private final LinkedHashMap<String, Object> telemetryData = new LinkedHashMap<>();
    private final List<Map<String, Object>> waypoints = new ArrayList<>();  // Store waypoints
    private final ExecutorService executorService = Executors.newFixedThreadPool(udpPorts.size() + 1);
    private final Map<Integer, Boolean> requestedMissionList = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> totalMissionItems = new ConcurrentHashMap<>();
    private boolean isTcpConnected = false;
    private double totalDistance = 0.0;
    private Double prevLat = null, prevLon = null;
    private double homeLat = 35.0766961;
    private double homeLon = 129.0921085;
    private double startTimeSeconds;
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
                    handleMavlinkMessage(message, port, udpSocket, senderAddress, senderPort);
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
                        int systemId = message.getOriginSystemId();
                        telemetryData.put("sysid", systemId);
                        lastMessageTime = System.currentTimeMillis();
                        if (message.getPayload() instanceof MissionCount missionCount) {
                            System.out.println("📌 Received MISSION_COUNT via TCP: " + missionCount.count());
                            requestMissionItemsTcp(socket, missionCount.count());
                        }
                        processTelemetryMessage(message);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ TCP Connection Lost: " + e.getMessage());
                isTcpConnected = false;
                sleep(5000);
            }
        }
    }

    private void handleMavlinkMessage(MavlinkMessage<?> message, int port, DatagramSocket udpSocket, InetAddress senderAddress, int senderPort) {
        String messageType = message.getPayload().getClass().getSimpleName();
       // System.out.println("📡 [Port " + port + "] Received: " + messageType + " from " + senderAddress.getHostAddress());

        if (message.getPayload() instanceof MissionCount missionCount) {
            totalMissionItems.put(port, missionCount.count());
            requestMissionItems(senderAddress, senderPort, port, udpSocket);
        }

        if (message.getPayload() instanceof MissionItemInt missionItemInt) {
            System.out.println("✅ [Port " + port + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }

        if (!requestedMissionList.get(port)) {
            requestMissionList(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }
    }

    private void requestMissionList(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
            connection.send1(255, 0, MissionRequestList.builder().targetSystem(1).targetComponent(1).build());
            DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
            udpSocket.send(packet);
            System.out.println("📡 [Port " + udpPort + "] Mission List request sent to " + address.getHostAddress());
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission List on port " + udpPort + ": " + e.getMessage());
        }
    }

    private void requestMissionItems(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        int missionCount = totalMissionItems.getOrDefault(udpPort, -1);
        if (missionCount <= 0) return;

        try {
            for (int i = 0; i < missionCount; i++) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
                connection.send1(255, 0, MissionRequestInt.builder().targetSystem(1).targetComponent(1).seq(i).build());
                DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
                udpSocket.send(packet);
                System.out.println("📡 [Port " + udpPort + "] Requested Mission Item " + i);
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

            System.out.println("📡 [Port " + remotePort + "] Mission List request sent via TCP to " + remoteAddress.getHostAddress());
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
                System.out.println("📡 [Port " + remotePort + "] Requested Mission Item " + i + " via TCP to " + remoteAddress.getHostAddress());
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
    private void processTelemetryMessage(MavlinkMessage<?> message) {
        Object payload = message.getPayload();
        double currentTimeSeconds = System.currentTimeMillis() / 1000.0;
        double timeInAir = currentTimeSeconds - startTimeSeconds;
        telemetryData.put("time_in_air", timeInAir);
        // Format time_in_air_min_sec as minutes.seconds (like 2.35 for 2 min 35 sec)
        int minutes = (int) (timeInAir / 60);
        int seconds = (int) (timeInAir % 60);
        telemetryData.put("time_in_air_min_sec", String.format("%d.%02d", minutes, seconds));

        if (payload instanceof MissionCurrent missionCurrent) {

            telemetryData.put("waypoints_count", missionCurrent.total());
        }

        if (payload instanceof MissionItemInt missionItemInt) {
            System.out.println("Received MissionItemInt: " + missionItemInt.seq());

            // Create a waypoint data map
            Map<String, Object> waypoint = new LinkedHashMap<>();
            waypoint.put("mission_seq", missionItemInt.seq());
            waypoint.put("mission_lat", missionItemInt.x() / 1e7);
            waypoint.put("mission_lon", missionItemInt.y() / 1e7);
            waypoint.put("mission_alt", missionItemInt.z());

            // Add to waypoints list
            waypoints.add(waypoint);
            telemetryData.put("waypoints", waypoints);  // Update telemetry data

            // Print the updated waypoints list
            System.out.println("Waypoints List: " + waypoints);
        }

        if (payload instanceof GlobalPositionInt globalPositionInt) {
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

        else if (payload instanceof VfrHud vfrHud) {
            telemetryData.put("airspeed", vfrHud.airspeed());
            telemetryData.put("ground speed", vfrHud.groundspeed());
            telemetryData.put("vertical_speed", vfrHud.climb());
        } else if (payload instanceof NavControllerOutput navControllerOutput) {
            telemetryData.put("wp_dist", navControllerOutput.wpDist());
        }  else if (payload instanceof Attitude attitude) {
            telemetryData.put("roll", Math.toDegrees(attitude.roll()));
            telemetryData.put("pitch", Math.toDegrees(attitude.pitch()));
            telemetryData.put("yaw", Math.toDegrees(attitude.yaw()));
        } else if (payload instanceof SysStatus sysStatus) {
            telemetryData.put("battery_voltage", sysStatus.voltageBattery());
            telemetryData.put("battery_current", sysStatus.currentBattery());
        } else if (payload instanceof Wind wind) {
            telemetryData.put("wind_vel", wind.speed());
        }
        emitTelemetry();
    }
    private void emitTelemetry() {


        // Print telemetry data to console
        telemetryData.forEach((key, value) -> {
            String outputValue = value.toString();

            // Limit to 20 words if key contains "waypoints"
            if (key.contains("waypoints")) {
                String[] words = outputValue.split("\\s+"); // Split by spaces
                if (words.length > 2) {
                    outputValue = String.join(" ", Arrays.copyOfRange(words, 0, 2)) + "...}]"; // Limit to 20 words
                }
            }

            if (key.contains("out")) {
                System.out.printf("\033[91m%-20s\033[0m: %s\n", key, outputValue);
            } else if (key.contains("al") || key.contains("dist") || key.contains("l")) {
                System.out.printf("\033[92m%-20s\033[0m: %s\n", key, outputValue);
            } else {
                System.out.printf("%-20s: %s\n", key, outputValue);
            }
        });

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
