To store telemetry data for both TCP and UDP (multi-client) and later send it to the frontend using WebSockets, you need an efficient data structure that keeps track of multiple clients, their IPs, ports, and the received telemetry data.

---

## **1. Data Structure to Store Telemetry Data**

Since multiple clients will send telemetry data over TCP and UDP, you need to track:
- **Client Identifier**: IP and port of the sender.
- **Protocol**: Whether the data came from TCP or UDP.
- **Telemetry Data**: The actual telemetry values received.
- **Timestamps**: To track when the data was received.

You can use a **dictionary (`dict`) with IP and port as keys** to store multiple clients' telemetry data.

### **Example Data Structure (Python)**
```python
telemetry_store = {
    ("192.168.1.10", 5000): {  # (Client IP, Port)
        "protocol": "TCP",
        "data": [
            {"timestamp": 1700000000, "altitude": 100, "speed": 50, "position": [34.56, -120.45]},
            {"timestamp": 1700000010, "altitude": 102, "speed": 52, "position": [34.57, -120.46]},
        ]
    },
    ("192.168.1.15", 6000): {  # Another client
        "protocol": "UDP",
        "data": [
            {"timestamp": 1700000005, "altitude": 90, "speed": 48, "position": [34.50, -120.40]},
        ]
    }
}
```
- The **keys** are `(IP, Port)` tuples.
- The **values** store protocol type (`TCP` or `UDP`) and a list of telemetry records.
- Each telemetry record has a **timestamp**, altitude, speed, and position (latitude/longitude).

---

## **2. Handling TCP and UDP Data Storage**
You will need a **TCP server** and a **UDP listener** to receive and store telemetry data.

### **TCP Server (Receives Data and Stores It)**
```python
import socket
import json
import time

telemetry_store = {}

def handle_tcp_client(client_socket, addr):
    global telemetry_store
    ip, port = addr
    while True:
        data = client_socket.recv(1024)
        if not data:
            break
        
        telemetry = json.loads(data.decode())  # Assume JSON formatted telemetry data
        entry = {
            "timestamp": time.time(),
            "altitude": telemetry["altitude"],
            "speed": telemetry["speed"],
            "position": telemetry["position"]
        }
        
        if (ip, port) not in telemetry_store:
            telemetry_store[(ip, port)] = {"protocol": "TCP", "data": []}
        telemetry_store[(ip, port)]["data"].append(entry)
        
        print(f"Received TCP data from {ip}:{port} -> {entry}")

    client_socket.close()

tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp_server.bind(("0.0.0.0", 5000))  # Listen on port 5000
tcp_server.listen(5)

while True:
    client_socket, addr = tcp_server.accept()
    handle_tcp_client(client_socket, addr)
```
---

### **UDP Listener (Handles Multiple Clients)**
```python
import socket

udp_server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp_server.bind(("0.0.0.0", 6000))  # Listen on port 6000

while True:
    data, addr = udp_server.recvfrom(1024)  # Receive UDP packet
    ip, port = addr
    telemetry = json.loads(data.decode())  # Assume JSON formatted telemetry
    
    entry = {
        "timestamp": time.time(),
        "altitude": telemetry["altitude"],
        "speed": telemetry["speed"],
        "position": telemetry["position"]
    }

    if (ip, port) not in telemetry_store:
        telemetry_store[(ip, port)] = {"protocol": "UDP", "data": []}
    telemetry_store[(ip, port)]["data"].append(entry)

    print(f"Received UDP data from {ip}:{port} -> {entry}")
```
---

## **3. Sending Data to Frontend via WebSockets**
You can use **`websockets`** library to send the stored telemetry data to the frontend.

### **WebSocket Server**
```python
import asyncio
import websockets
import json

async def send_telemetry(websocket, path):
    while True:
        await websocket.send(json.dumps(telemetry_store))
        await asyncio.sleep(1)  # Send updates every second

start_server = websockets.serve(send_telemetry, "0.0.0.0", 8080)

asyncio.get_event_loop().run_until_complete(start_server)
asyncio.get_event_loop().run_forever()
```
---

## **4. WebSocket Client (Frontend)**
Your frontend (React/JavaScript) can connect and receive telemetry updates.
```javascript
const ws = new WebSocket("ws://your_server_ip:8080");

ws.onmessage = (event) => {
    const telemetryData = JSON.parse(event.data);
    console.log("Telemetry Data:", telemetryData);
};
```
---

## **Summary**
- **Store telemetry data** using a dictionary with IP and port as keys.
- **Handle TCP and UDP** using Python's `socket` module.
- **Send stored data to the frontend** using a WebSocket server (`websockets` module).
- **Frontend receives real-time updates** through WebSocket.

Would you like to integrate this with MongoDB for persistent storage? 🚀

### **Storing Telemetry Data in Java (Similar to Python Dictionary Structure)**  
To store telemetry data in Java in a way similar to your **Python dictionary**, we will use:  
- **`ConcurrentHashMap`** for thread safety  
- **`Pair<IP, Port>`** as the key (using `AbstractMap.SimpleEntry<String, Integer>`)  
- **A `TelemetryEntry` class** to store protocol type and data  

---

## **1. Define Data Structures**
```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.AbstractMap;

class TelemetryData {
    long timestamp;
    double altitude;
    double speed;
    double latitude;
    double longitude;

    public TelemetryData(long timestamp, double altitude, double speed, double latitude, double longitude) {
        this.timestamp = timestamp;
        this.altitude = altitude;
        this.speed = speed;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public String toString() {
        return "{timestamp: " + timestamp + ", altitude: " + altitude + ", speed: " + speed +
                ", position: [" + latitude + ", " + longitude + "]}";
    }
}

class TelemetryEntry {
    String protocol;
    List<TelemetryData> data;

    public TelemetryEntry(String protocol) {
        this.protocol = protocol;
        this.data = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "{protocol: " + protocol + ", data: " + data + "}";
    }
}

public class TelemetryStore {
    public static ConcurrentHashMap<AbstractMap.SimpleEntry<String, Integer>, TelemetryEntry> telemetryStore = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // Simulate adding telemetry data
        addTelemetryData("192.168.1.10", 5000, "TCP", 1700000000, 100, 50, 34.56, -120.45);
        addTelemetryData("192.168.1.10", 5000, "TCP", 1700000010, 102, 52, 34.57, -120.46);
        addTelemetryData("192.168.1.15", 6000, "UDP", 1700000005, 90, 48, 34.50, -120.40);

        // Print stored telemetry data
        System.out.println(telemetryStore);
    }

    public static void addTelemetryData(String ip, int port, String protocol, long timestamp, double altitude, double speed, double latitude, double longitude) {
        AbstractMap.SimpleEntry<String, Integer> key = new AbstractMap.SimpleEntry<>(ip, port);
        TelemetryData telemetry = new TelemetryData(timestamp, altitude, speed, latitude, longitude);

        telemetryStore.computeIfAbsent(key, k -> new TelemetryEntry(protocol)).data.add(telemetry);
    }
}
```
---

## **2. Explanation**
### ✅ **Data Structure:**
- **Key** → `AbstractMap.SimpleEntry<String, Integer>` (IP, Port)
- **Value** → `TelemetryEntry` (Contains protocol type and list of telemetry data)

### ✅ **Methods:**
- **`addTelemetryData()`** → Adds new telemetry data into the storage
- **`computeIfAbsent()`** → Ensures a new `TelemetryEntry` is created only if the IP-Port pair does not exist

---

## **3. Sample Output**
```json
{
    (192.168.1.10, 5000): {protocol: TCP, data: [
        {timestamp: 1700000000, altitude: 100.0, speed: 50.0, position: [34.56, -120.45]},
        {timestamp: 1700000010, altitude: 102.0, speed: 52.0, position: [34.57, -120.46]}
    ]},
    (192.168.1.15, 6000): {protocol: UDP, data: [
        {timestamp: 1700000005, altitude: 90.0, speed: 48.0, position: [34.50, -120.40]}
    ]}
}
```



