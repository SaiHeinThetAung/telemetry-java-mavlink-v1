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



