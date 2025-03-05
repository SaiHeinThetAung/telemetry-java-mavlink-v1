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
    private final ExecutorService executorService = Executors.newFixedThreadPool(udpPorts.size() + 1);
    private final Map<Integer, Boolean> requestedMissionList = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> totalMissionItems = new ConcurrentHashMap<>();
    private boolean isTcpConnected = false;
    private long lastMessageTime = System.currentTimeMillis();

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
            System.out.println("\uD83D\uDCE1 [TcpPort " + tcpPort + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }


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
            System.out.println("✅ [UdpPort " + port + "] Received Mission Item: Seq " + missionItemInt.seq() +
                    " (Lat: " + missionItemInt.x() + ", Lon: " + missionItemInt.y() + ", Alt: " + missionItemInt.z() + ")");
        }
        if (!requestedMissionList.get(port)) {
            requestMissionListUdp(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }



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
